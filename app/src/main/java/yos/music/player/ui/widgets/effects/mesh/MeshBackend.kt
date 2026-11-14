/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * Ported from packages/core/src/bg-render/mesh-renderer/index.ts: the fade between covers and the flow of time.
 */

package yos.music.player.ui.widgets.effects.mesh

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Fades the covers in and out like AMLL does: a new cover fades in over the old one,
 * then the old one is dropped. Without a cover the covers fade out.
 */
internal class MeshAnimator<S : Any>(private val dispose: (S) -> Unit) {
    class Entry<S>(val state: S, var alpha: Float)

    val entries = ArrayList<Entry<S>>()
    private var noCover = true

    fun add(state: S) {
        entries.add(Entry(state, 0f))
        noCover = false
    }

    fun fadeOut() {
        noCover = true
    }

    /** Moves the fades on by [deltaMs]. Returns true when nothing is changing any more */
    fun step(deltaMs: Float): Boolean {
        val latest = entries.lastOrNull() ?: return true
        val deltaFactor = deltaMs / 500f
        if (noCover) {
            var hasActive = false
            for (i in entries.indices.reversed()) {
                val entry = entries[i]
                if (entry.alpha <= -0.1f) {
                    dispose(entry.state)
                    entries.removeAt(i)
                } else {
                    entry.alpha = max(-0.1f, entry.alpha - deltaFactor)
                    hasActive = true
                }
            }
            return !hasActive
        }
        if (latest.alpha >= 1.1f) {
            while (entries.size > 1) dispose(entries.removeAt(0).state)
        } else {
            latest.alpha = min(1.1f, latest.alpha + deltaFactor)
        }
        return entries.size == 1 && latest.alpha >= 1.1f
    }

    fun release() {
        for (entry in entries) dispose(entry.state)
        entries.clear()
    }

    companion object {
        /** The alpha a cover is drawn with */
        fun ease(alpha: Float): Float {
            val x = alpha.coerceIn(0f, 1f)
            return (-(cos(PI * x) - 1) / 2).toFloat()
        }
    }
}

/**
 * What the OpenGL ES and the Vulkan renderer have in common. A backend only creates and destroys the
 * GPU side of a cover and paints them, the timing is the same for both. Runs on the render thread.
 */
internal abstract class MeshBackend<S : Any> {
    /** Applies to the next cover, except for the speed, the vignette and the grain which apply at once */
    var settings = MeshSettings()

    private val animator = MeshAnimator<S> { destroyState(it) }
    private var frameTime = 0f
    private var volume = 0f
    private var smoothedVolume = 0f

    protected abstract fun createState(pixels: IntArray, geometry: MeshGeometry): S?
    protected abstract fun destroyState(state: S)

    /** Draws the covers in order, each one with the alpha of its fade, which is already eased */
    protected abstract fun paint(entries: List<MeshAnimator.Entry<S>>, angle: Float, volume: Float)

    /** False while there is nothing to draw on yet */
    protected open fun canPaint(): Boolean = true
    protected abstract fun releaseGpu()

    /** The cover as SIZE x SIZE processed ARGB pixels, or null when there is no cover */
    fun setCover(pixels: IntArray?) {
        if (pixels == null) {
            animator.fadeOut()
            return
        }
        val geometry = buildHermiteMesh(controlPointPresetForStyle(settings.style), settings.detail)
        val state = createState(pixels, geometry) ?: return
        animator.add(state)
    }

    /** The bass level from 0 to 1. AMLL scales it by 1/10 before it reaches the shader */
    fun setVolume(level: Float) {
        volume = level.coerceIn(0f, 1f) / 10f
    }

    /**
     * Draws one frame. The texture only turns while [advanceTime] is true, a fade still plays.
     * Returns true when nothing is changing any more, so the caller can stop drawing.
     */
    fun draw(deltaMs: Float, advanceTime: Boolean): Boolean {
        if (advanceTime) frameTime += deltaMs * settings.flowSpeed
        val canBeStatic = animator.step(deltaMs)
        if (!canPaint()) return canBeStatic
        smoothedVolume += (volume - smoothedVolume) * min(1f, deltaMs / (if (volume > smoothedVolume) 60f else 200f))
        paint(animator.entries, (frameTime / 10000f + smoothedVolume) * 2f, smoothedVolume)
        return canBeStatic
    }

    fun release() {
        animator.release()
        releaseGpu()
    }
}
