package yos.music.player.ui.widgets.effects.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshGradientMathTest {
    @Test
    fun everyPresetBuildsAMeshWithValidIndices() {
        for (preset in controlPointPresets) {
            val mesh = buildHermiteMesh(preset, 10)
            val count = mesh.vertices.size / 4
            assertEquals((preset.width - 1) * 10 * (preset.height - 1) * 10, count)
            assertTrue(mesh.vertices.all { it.isFinite() })
            assertTrue(mesh.indices.all { (it.toInt() and 0xFFFF) < count })
        }
    }

    @Test
    fun generatedPresetsKeepTheirBordersFixed() {
        for (seed in 0 until 50) {
            val preset = generateControlPoints(6, 6, Random(seed))
            assertEquals(36, preset.conf.size)
            for (point in preset.conf) {
                assertTrue(point.x.isFinite() && point.y.isFinite())
                if (point.cx == 0) assertEquals(-1.0, point.x, 0.0)
                if (point.cx == 5) assertEquals(1.0, point.x, 0.0)
                if (point.cy == 0) assertEquals(-1.0, point.y, 0.0)
                if (point.cy == 5) assertEquals(1.0, point.y, 0.0)
            }
        }
    }

    @Test
    fun aFlatGrayCoverStaysFlat() {
        // 128 becomes 96 after the contrast, saturation and brightness steps, and a blur keeps it
        val out = CoverTexture.process(IntArray(32 * 32) { 0xFF808080.toInt() })
        assertTrue(out.all { it == 0xFF606060.toInt() })
    }

    @Test
    fun bassLevelFollowsTheLowFrequencies() {
        val analyzer = BassAnalyzer({ 1f }) { }
        val quiet = ByteArray(1024)
        assertEquals(0f, analyzer.level(quiet, 44_100_000), 0f)

        // The bins at 43, 86 and 129 Hz are loud
        val loud = ByteArray(1024)
        for (bin in 1..3) loud[bin * 2] = 100
        assertEquals(1f, analyzer.level(loud, 44_100_000), 0.001f)

        // Treble does not count
        val treble = ByteArray(1024)
        for (bin in 100..120) treble[bin * 2] = 100
        assertEquals(0f, BassAnalyzer({ 1f }) { }.level(treble, 44_100_000), 0f)
    }

    @Test
    fun bassSensitivityScalesTheReaction() {
        val loud = ByteArray(1024)
        for (bin in 1..3) loud[bin * 2] = 100
        val soft = ByteArray(1024)
        for (bin in 1..3) soft[bin * 2] = 60

        // The analyzer compares with the loudest recent bass, which fades by 1% on every reading: 99 here
        fun primed() = BassAnalyzer({ 1f }) { }.also { it.level(loud, 44_100_000) }
        for (sensitivity in listOf(0f, 0.5f, 1f, 1.5f, 2f)) {
            val expected = ((60f / 99f * sensitivity - 0.5f) / 0.5f).coerceIn(0f, 1f)
            assertEquals(expected, primed().level(soft, 44_100_000, sensitivity), 0.001f)
        }
        // More sensitivity never reacts less
        assertTrue(primed().level(soft, 44_100_000, 2f) > primed().level(soft, 44_100_000, 1f))
    }

    @Test
    fun styleSelectsThePreset() {
        for (style in 1..6) assertTrue(controlPointPresetForStyle(style, Random(1)) === controlPointPresets[style - 1])
        assertEquals(6, controlPointPresetForStyle(7, Random(1)).width)
        assertTrue(controlPointPresetForStyle(0, Random(1)).width in 4..6)
        assertTrue(controlPointPresetForStyle(99, Random(1)).width in 4..6)
    }

    @Test
    fun brightnessScalesTheCoverAndZeroSaturationMakesItGray() {
        val flat = IntArray(32 * 32) { 0xFF808080.toInt() }
        // 128 stays 128 through contrast and saturation, then brightness 0.5 halves it
        assertTrue(CoverTexture.process(flat, brightness = 0.5).all { it == 0xFF404040.toInt() })

        val colored = IntArray(32 * 32) { 0xFFC86432.toInt() }
        assertTrue(CoverTexture.process(colored, saturation = 0.0).all {
            ((it shr 16) and 0xFF) == ((it shr 8) and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF)
        })
    }
}
