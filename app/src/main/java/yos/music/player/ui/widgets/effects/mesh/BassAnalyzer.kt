/*
 * Mesh gradient background, ported to Kotlin from Apple Music-like Lyrics (AMLL)
 * https://github.com/amll-dev/applemusic-like-lyrics
 * Original work: Copyright (C) SteveXMH and the AMLL contributors, GNU AGPL-3.0.
 * The bass analyzer is new, AMLL gets the bass level from its own audio processing.
 */

package yos.music.player.ui.widgets.effects.mesh

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

/**
 * Measures the bass of the audio session of the player and reports it as a level from 0 to 1.
 * The Visualizer needs the record audio permission. It only reads what this app plays.
 */
internal class BassAnalyzer(private val sensitivity: () -> Float, private val onLevel: (Float) -> Unit) {
    private var visualizer: Visualizer? = null
    private var peak = 0f

    /** Returns false when the audio session cannot be analysed, for example without the permission */
    fun start(audioSessionId: Int): Boolean {
        stop()
        if (audioSessionId <= 0) return false
        return try {
            val created = Visualizer(audioSessionId)
            created.captureSize = Visualizer.getCaptureSizeRange()[1]
            created.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) onLevel(level(fft, samplingRate, sensitivity()))
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            created.enabled = true
            visualizer = created
            true
        } catch (e: Exception) {
            Log.w("MeshGradient", "The bass analyzer did not start", e)
            false
        }
    }

    fun stop() {
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (_: Exception) {
            }
        }
        visualizer = null
        peak = 0f
        onLevel(0f)
    }

    /** The FFT holds the real part of the first and last bin, then a real and imaginary byte per bin */
    internal fun level(fft: ByteArray, samplingRateMilliHz: Int, sensitivity: Float = 1f): Float {
        val binHz = samplingRateMilliHz / 1000f / fft.size
        if (binHz <= 0f) return 0f
        val first = max(1, Math.ceil((LOW_HZ / binHz).toDouble()).toInt())
        val last = max(first, (HIGH_HZ / binHz).toInt())
        var sum = 0f
        var count = 0
        for (bin in first..last) {
            if (bin * 2 + 1 >= fft.size) break
            sum += hypot(fft[bin * 2].toFloat(), fft[bin * 2 + 1].toFloat())
            count++
        }
        if (count == 0) return 0f
        val magnitude = sum / count
        // Compared with the loudest recent bass, so the system volume does not matter
        peak = max(magnitude, peak * 0.99f)
        // A higher sensitivity reacts to softer bass
        val relative = magnitude / max(peak, MIN_PEAK) * sensitivity
        return ((relative - 0.5f) / 0.5f).coerceIn(0f, 1f)
    }

    private companion object {
        const val LOW_HZ = 40f
        const val HIGH_HZ = 150f

        // Below this, silence and noise would be stretched to a full level
        const val MIN_PEAK = 24f
    }
}
