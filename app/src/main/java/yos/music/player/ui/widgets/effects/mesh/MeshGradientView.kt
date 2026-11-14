/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The view and its render thread are new, they host the ported renderers.
 */

package yos.music.player.ui.widgets.effects.mesh

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The animated mesh gradient. It lives in a TextureView, so it follows the transforms and
 * the alpha of the compose layout around it. The drawing runs on its own thread, with Vulkan or
 * OpenGL ES depending on [MeshSettings.backend]. The backend is chosen when the view is made.
 */
class MeshGradientView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null
    private var cover: IntArray? = null
    private var flowing = true
    private var bassLevel = 0f
    private var settings = MeshSettings()

    init {
        surfaceTextureListener = this
    }

    /** The cover as processed pixels of [CoverTexture], or null for no cover */
    internal fun setCover(pixels: IntArray?) {
        if (cover === pixels) return
        cover = pixels
        thread?.setCover(pixels)
    }

    /** True lets the gradient flow. False stops it, a fade between two covers still plays */
    fun setFlowing(flowing: Boolean) {
        if (this.flowing == flowing) return
        this.flowing = flowing
        thread?.let {
            it.flowing = flowing
            it.wake()
        }
    }

    /** Changes how the gradient is drawn. A new detail or style builds the mesh of the cover again */
    fun applySettings(new: MeshSettings) {
        val old = settings
        if (old == new) return
        settings = new
        val renderThread = thread ?: return
        if (old.renderScale != new.renderScale) {
            surfaceTexture?.setDefaultBufferSize(scaled(width, new.renderScale), scaled(height, new.renderScale))
        }
        renderThread.applySettings(new, rebuild = old.detail != new.detail || old.style != new.style)
    }

    /** The bass level from 0 to 1 */
    fun setBassLevel(level: Float) {
        bassLevel = level
        thread?.bass = level
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val renderThread = RenderThread(texture, width, height, settings, cover)
        renderThread.flowing = flowing
        renderThread.bass = bassLevel
        thread = renderThread
        renderThread.start()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // The TextureView resets the buffer size to the view size, so the smaller size is set again
        texture.setDefaultBufferSize(scaled(width, settings.renderScale), scaled(height, settings.renderScale))
        thread?.wake()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // The EGL or Vulkan surface has to be gone before the texture is released
        thread?.shutdown()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    private class RenderThread(
        private val texture: SurfaceTexture,
        width: Int,
        height: Int,
        @Volatile private var settings: MeshSettings,
        @Volatile private var cover: IntArray?
    ) : Thread("MeshGradientRender") {
        private val queue = LinkedBlockingQueue<(MeshBackend<*>) -> Unit>()
        private val bufferWidth = scaled(width, settings.renderScale)
        private val bufferHeight = scaled(height, settings.renderScale)
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        @Volatile
        private var running = true

        @Volatile
        var flowing = true

        @Volatile
        var bass = 0f

        @Volatile
        var frameMs = frameInterval(settings.fps)

        init {
            texture.setDefaultBufferSize(bufferWidth, bufferHeight)
        }

        fun setCover(pixels: IntArray?) {
            cover = pixels
            queue.offer { it.setCover(pixels) }
        }

        fun applySettings(new: MeshSettings, rebuild: Boolean) {
            settings = new
            frameMs = frameInterval(new.fps)
            queue.offer {
                it.settings = new
                if (rebuild) it.setCover(cover)
            }
        }

        fun wake() {
            queue.offer { }
        }

        fun shutdown() {
            running = false
            wake()
            try {
                join(1000)
            } catch (_: InterruptedException) {
            }
        }

        override fun run() {
            try {
                if (settings.backend == MeshSettings.VULKAN && VulkanMeshNative.available && runVulkan()) return
                runOpenGl()
            } catch (e: Throwable) {
                Log.e("MeshGradient", "The render thread stopped", e)
            }
        }

        /** Returns false when Vulkan could not be used, so OpenGL ES can take over */
        private fun runVulkan(): Boolean {
            val nativeSurface = Surface(texture)
            val handle = VulkanMeshNative.create(nativeSurface, bufferWidth, bufferHeight)
            if (handle == 0L) {
                nativeSurface.release()
                Log.w("MeshGradient", "Vulkan is not available on this phone, OpenGL ES is used")
                return false
            }
            val backend = VulkanMeshBackend(handle)
            try {
                prepare(backend)
                loop(backend, present = { true }, beforeDraw = { })
                return true
            } catch (e: Throwable) {
                Log.e("MeshGradient", "Vulkan stopped", e)
                return !running
            } finally {
                try {
                    backend.release()
                } finally {
                    VulkanMeshNative.destroy(handle)
                    nativeSurface.release()
                }
            }
        }

        private fun runOpenGl() {
            try {
                initEgl()
                val renderer = MeshGradientRenderer()
                try {
                    renderer.onSurfaceCreated()
                    prepare(renderer)
                    loop(renderer, present = { presentOpenGl() }, beforeDraw = { updateSize(renderer) })
                } finally {
                    // The context is still current here, the GPU objects need it to be deleted
                    renderer.release()
                }
            } finally {
                releaseEgl()
            }
        }

        private fun prepare(backend: MeshBackend<*>) {
            backend.settings = settings
            backend.setCover(cover)
        }

        private fun loop(backend: MeshBackend<*>, present: () -> Boolean, beforeDraw: () -> Unit) {
            var last = System.nanoTime()
            while (running) {
                while (true) (queue.poll() ?: break).invoke(backend)
                beforeDraw()

                val frameStart = System.nanoTime()
                val delta = minOf((frameStart - last) / 1_000_000f, 100f)
                last = frameStart
                backend.setVolume(bass)
                val canBeStatic = backend.draw(delta, flowing)
                if (!present()) break

                if (canBeStatic && !flowing) {
                    // Nothing moves, so wait for the next change
                    queue.take().invoke(backend)
                    last = System.nanoTime()
                    continue
                }
                val wait = frameMs - (System.nanoTime() - frameStart) / 1_000_000L
                // A new cover wakes the thread early
                if (wait > 0) queue.poll(wait, TimeUnit.MILLISECONDS)?.invoke(backend)
            }
        }

        private fun presentOpenGl(): Boolean {
            if (EGL14.eglSwapBuffers(display, surface)) return true
            Log.w("MeshGradient", "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            return false
        }

        private fun updateSize(renderer: MeshGradientRenderer) {
            val size = IntArray(1)
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, size, 0)
            val newWidth = size[0]
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, size, 0)
            val newHeight = size[0]
            if (newWidth != surfaceWidth || newHeight != surfaceHeight) {
                surfaceWidth = newWidth
                surfaceHeight = newHeight
                renderer.onSizeChanged(newWidth, newHeight)
            }
        }

        private fun initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not initialize" }

            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "No EGL config"
            }
            val config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context" }
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "No EGL surface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL context is not current" }
            EGL14.eglSwapInterval(display, 1)
        }

        private fun releaseEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }
    }
}

private fun frameInterval(fps: Int) = 1000L / fps.coerceIn(1, 120)

// The gradient is smooth, so a smaller drawing scaled up by the TextureView looks the same
private fun scaled(size: Int, scale: Float) = max(2, (size * scale.coerceIn(0.1f, 1f)).toInt())
