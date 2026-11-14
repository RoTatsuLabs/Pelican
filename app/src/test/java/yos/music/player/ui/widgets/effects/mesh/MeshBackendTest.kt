package yos.music.player.ui.widgets.effects.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshBackendTest {
    /** A backend that only records what it is asked to do */
    private class Fake : MeshBackend<Int>() {
        var created = 0
        val destroyed = ArrayList<Int>()
        var lastEntries = emptyList<Pair<Int, Float>>()
        var lastAngle = 0f
        var lastVolume = 0f
        var paints = 0
        var ready = true

        override fun createState(pixels: IntArray, geometry: MeshGeometry): Int? = ++created
        override fun destroyState(state: Int) { destroyed.add(state) }
        override fun paint(entries: List<MeshAnimator.Entry<Int>>, angle: Float, volume: Float) {
            paints++
            lastEntries = entries.map { it.state to it.alpha }
            lastAngle = angle
            lastVolume = volume
        }
        override fun canPaint() = ready
        override fun releaseGpu() {}
    }

    private val pixels = IntArray(CoverTexture.SIZE * CoverTexture.SIZE)

    @Test
    fun aNewCoverFadesInOverHalfASecondAndThenIsStatic() {
        val backend = Fake()
        backend.setCover(pixels)
        // 1.1 is reached after 550 ms, a tenth more than the fade itself
        assertTrue(!backend.draw(250f, true))
        assertEquals(0.5f, backend.lastEntries.single().second, 1e-6f)
        assertTrue(!backend.draw(250f, true))
        // A single cover is static in the step that finishes its fade
        assertTrue(backend.draw(100f, true))
        assertEquals(1.1f, backend.lastEntries.single().second, 1e-6f)
        assertTrue(backend.draw(16f, true))
    }

    @Test
    fun theOldCoverIsDroppedOnceTheNewOneIsFullyIn() {
        val backend = Fake()
        backend.setCover(pixels)
        repeat(4) { backend.draw(200f, true) }
        backend.setCover(pixels)
        assertEquals(2, backend.created)
        assertTrue(!backend.draw(300f, true))
        assertEquals(listOf(1 to 1.1f, 2 to 0.6f), backend.lastEntries.map { it.first to Math.round(it.second * 10) / 10f })
        repeat(2) { backend.draw(300f, true) }
        // The step after the new cover reached 1.1 drops the old one
        assertTrue(backend.draw(16f, true))
        assertEquals(listOf(1), backend.destroyed)
        assertEquals(1, backend.lastEntries.size)
    }

    @Test
    fun withoutACoverTheOldOnesFadeOutAndAreDestroyed() {
        val backend = Fake()
        backend.setCover(pixels)
        repeat(4) { backend.draw(200f, true) }
        backend.setCover(null)
        var frames = 0
        while (!backend.draw(100f, true)) { frames++; assertTrue(frames < 100) }
        assertEquals(listOf(1), backend.destroyed)
        assertTrue(backend.draw(16f, true))
    }

    @Test
    fun timeOnlyTurnsWhenAllowedAndFollowsTheSpeed() {
        val backend = Fake()
        backend.settings = MeshSettings(flowSpeed = 2f)
        backend.setCover(pixels)
        backend.draw(1000f, true)
        // 1000 ms at speed 2 is 2000 units, the angle is (2000 / 10000) * 2
        assertEquals(0.4f, backend.lastAngle, 1e-5f)
        backend.draw(1000f, false)
        assertEquals(0.4f, backend.lastAngle, 1e-5f)
        backend.settings = MeshSettings(flowSpeed = 4f)
        backend.draw(500f, true)
        assertEquals((2000f + 2000f) / 10000f * 2f, backend.lastAngle, 1e-5f)
    }

    @Test
    fun bassIsSmoothedQuickerUpThanDown() {
        val backend = Fake()
        backend.setCover(pixels)
        backend.setVolume(1f)
        backend.draw(60f, false)
        // The target is 1/10, a 60 ms step covers all of the way up
        assertEquals(0.1f, backend.lastVolume, 1e-5f)
        backend.setVolume(0f)
        backend.draw(100f, false)
        assertEquals(0.05f, backend.lastVolume, 1e-5f)
        backend.setVolume(5f)
        assertEquals(1f, backend.run { draw(1000f, false); lastVolume * 10f }, 1e-5f)
    }

    @Test
    fun nothingIsPaintedBeforeTheSurfaceIsReady() {
        val backend = Fake()
        backend.ready = false
        backend.setCover(pixels)
        backend.draw(100f, true)
        assertEquals(0, backend.paints)
        backend.ready = true
        backend.draw(100f, true)
        assertEquals(1, backend.paints)
    }

    @Test
    fun theEasingStartsAndEndsAtTheEdges() {
        assertEquals(0f, MeshAnimator.ease(0f), 1e-6f)
        assertEquals(0.5f, MeshAnimator.ease(0.5f), 1e-6f)
        assertEquals(1f, MeshAnimator.ease(1.1f), 1e-6f)
        assertEquals(0f, MeshAnimator.ease(-0.1f), 1e-6f)
    }
}
