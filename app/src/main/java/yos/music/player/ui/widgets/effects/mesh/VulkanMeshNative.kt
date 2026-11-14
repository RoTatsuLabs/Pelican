/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The Vulkan bridge is new code written for Pelican, the native part is in src/main/cpp.
 */

package yos.music.player.ui.widgets.effects.mesh

import android.view.Surface
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/** The bridge to the Vulkan renderer in src/main/cpp. [available] is false when the library did not load */
internal object VulkanMeshNative {
    val available: Boolean = try {
        System.loadLibrary("pelican_vulkan")
        true
    } catch (_: Throwable) {
        false
    }

    /** Returns 0 when the phone cannot draw with Vulkan */
    external fun create(surface: Surface, width: Int, height: Int): Long
    external fun destroy(handle: Long)

    /** Returns the id of the cover, or -1 */
    external fun addState(handle: Long, vertices: FloatArray, indices: ShortArray, cover: IntArray): Int
    external fun removeState(handle: Long, id: Int)
    external fun draw(
        handle: Long, ids: IntArray, alphas: FloatArray, count: Int,
        sinAngle: Float, cosAngle: Float, volume: Float, vignette: Float, grain: Float
    ): Boolean
}

/** Draws the mesh gradient with Vulkan. The ids are those of the native renderer */
internal class VulkanMeshBackend(private val handle: Long) : MeshBackend<Int>() {
    private val ids = IntArray(MAX_STATES)
    private val alphas = FloatArray(MAX_STATES)
    private var failures = 0

    override fun createState(pixels: IntArray, geometry: MeshGeometry): Int? {
        val id = VulkanMeshNative.addState(handle, geometry.vertices, geometry.indices, pixels)
        return if (id > 0) id else null
    }

    override fun destroyState(state: Int) = VulkanMeshNative.removeState(handle, state)

    override fun paint(entries: List<MeshAnimator.Entry<Int>>, angle: Float, volume: Float) {
        val count = min(entries.size, MAX_STATES)
        for (i in 0 until count) {
            ids[i] = entries[i].state
            alphas[i] = MeshAnimator.ease(entries[i].alpha)
        }
        val drawn = VulkanMeshNative.draw(
            handle, ids, alphas, count, sin(angle), cos(angle), volume, settings.vignette, settings.grain
        )
        // A lost surface comes back by itself, a broken device does not and OpenGL ES has to take over
        failures = if (drawn) 0 else failures + 1
        check(failures < MAX_FAILURES) { "Vulkan keeps failing to draw" }
    }

    /** The native renderer is destroyed by the render thread */
    override fun releaseGpu() {}

    private companion object {
        const val MAX_STATES = 8
        const val MAX_FAILURES = 120
    }
}
