/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The settings are new code written for Pelican.
 */

package yos.music.player.ui.widgets.effects.mesh

/** What the user can change about how the gradient is drawn. The defaults match AMLL where it has one */
data class MeshSettings(
    /** Size of the drawing compared with the screen, from 0.25 to 1 */
    val renderScale: Float = 0.5f,
    val fps: Int = 30,
    /** How fast the texture turns, 2 is what AMLL uses */
    val flowSpeed: Float = 2f,
    /** Vertices per patch side, a smoother surface costs more */
    val detail: Int = 30,
    /** See [controlPointPresetForStyle] */
    val style: Int = 0,
    /** 1 is what AMLL uses, 0 is none */
    val vignette: Float = 1f,
    /** 1 only hides color banding like AMLL does, more is visible grain */
    val grain: Float = 1f,
    /** [OPENGL] or [VULKAN]. Vulkan falls back to OpenGL ES when the phone cannot use it */
    val backend: Int = OPENGL
) {
    companion object {
        const val OPENGL = 0
        const val VULKAN = 1
    }
}
