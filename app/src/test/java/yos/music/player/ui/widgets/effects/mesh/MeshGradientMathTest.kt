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
        val analyzer = BassAnalyzer { }
        val quiet = ByteArray(1024)
        assertEquals(0f, analyzer.level(quiet, 44_100_000), 0f)

        // The bins at 43, 86 and 129 Hz are loud
        val loud = ByteArray(1024)
        for (bin in 1..3) loud[bin * 2] = 100
        assertEquals(1f, analyzer.level(loud, 44_100_000), 0.001f)

        // Treble does not count
        val treble = ByteArray(1024)
        for (bin in 100..120) treble[bin * 2] = 100
        assertEquals(0f, BassAnalyzer { }.level(treble, 44_100_000), 0f)
    }
}
