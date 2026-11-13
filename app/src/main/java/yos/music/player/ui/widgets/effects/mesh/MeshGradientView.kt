/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The view and its render thread are new, they host the ported renderer.
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
import android.view.TextureView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The animated mesh gradient. It lives in a TextureView, so it follows the transforms and
 * the alpha of the compose layout around it. The drawing runs on its own thread.
 */
class MeshGradientView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null
    private var cover: IntArray? = null
    private var flowing = true
    private var bassLevel = 0f

    init {
        surfaceTextureListener = this
    }

    /** The cover as processed pixels of [CoverTexture], or null for no cover */
    internal fun setCover(pixels: IntArray?) {
        if (cover === pixels) return
        cover = pixels
        thread?.post { it.setCover(pixels) }
    }

    /** True lets the gradient flow. False stops it, a fade between two covers still plays */
    fun setFlowing(flowing: Boolean) {
        if (this.flowing == flowing) return
        this.flowing = flowing
        thread?.let {
            it.flowing = flowing
            it.post { }
        }
    }

    /** The bass level from 0 to 1 */
    fun setBassLevel(level: Float) {
        bassLevel = level
        thread?.bass = level
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val renderThread = RenderThread(texture, width, height)
        renderThread.flowing = flowing
        renderThread.bass = bassLevel
        val pixels = cover
        renderThread.post { it.setCover(pixels) }
        thread = renderThread
        renderThread.start()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // The TextureView resets the buffer size to the view size, so the smaller size is set again
        texture.setDefaultBufferSize(scaled(width), scaled(height))
        thread?.post { }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // The EGL surface has to be gone before the texture is released
        thread?.shutdown()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    private class RenderThread(
        private val texture: SurfaceTexture, width: Int, height: Int
    ) : Thread("MeshGradientRender") {
        private val queue = LinkedBlockingQueue<(MeshGradientRenderer) -> Unit>()
        private val renderer = MeshGradientRenderer()
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

        init {
            texture.setDefaultBufferSize(scaled(width), scaled(height))
        }

        fun post(task: (MeshGradientRenderer) -> Unit) {
            queue.offer(task)
        }

        fun shutdown() {
            running = false
            queue.offer { }
            try {
                join(1000)
            } catch (_: InterruptedException) {
            }
        }

        override fun run() {
            try {
                initEgl()
                renderer.onSurfaceCreated()
                loop()
            } catch (e: Throwable) {
                Log.e("MeshGradient", "The render thread stopped", e)
            } finally {
                releaseEgl()
            }
        }

        private fun loop() {
            var last = System.nanoTime()
            while (running) {
                while (true) (queue.poll() ?: break).invoke(renderer)
                updateSize()

                val frameStart = System.nanoTime()
                val delta = minOf((frameStart - last) / 1_000_000f, 100f)
                last = frameStart
                renderer.setVolume(bass)
                val canBeStatic = renderer.draw(delta, flowing)
                if (!EGL14.eglSwapBuffers(display, surface)) {
                    Log.w("MeshGradient", "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                    break
                }

                if (canBeStatic && !flowing) {
                    // Nothing moves, so wait for the next change
                    queue.take().invoke(renderer)
                    last = System.nanoTime()
                    continue
                }
                val wait = FRAME_MS - (System.nanoTime() - frameStart) / 1_000_000L
                // A new cover wakes the thread early
                if (wait > 0) queue.poll(wait, TimeUnit.MILLISECONDS)?.invoke(renderer)
            }
        }

        private fun updateSize() {
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
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, surface, surface, context)
                renderer.release()
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    private companion object {
        // 30 frames per second saves battery, the gradient moves slowly
        const val FRAME_MS = 33L

        // The gradient is smooth, so it is drawn at half the size and scaled up by the TextureView
        const val RENDER_SCALE = 0.5f

        fun scaled(size: Int) = max(2, (size * RENDER_SCALE).toInt())
    }
}
