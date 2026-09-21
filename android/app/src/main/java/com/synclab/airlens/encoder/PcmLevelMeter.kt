package com.synclab.airlens.encoder

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Instantaneous level of one PCM buffer in dBFS (0 dBFS = digital full scale, 32768).
 *
 * Both values are clamped to [PcmLevelMeter.FLOOR_DBFS] so callers never see -infinity.
 */
data class PcmLevel(val rmsDbfs: Float, val peakDbfs: Float)

/**
 * Measures RMS and peak level of a 16-bit little-endian signed PCM buffer.
 *
 * Pure arithmetic over the bytes AudioRecord has already written: O(n) in the sample count,
 * no intermediate copies, and the only allocation is the returned [PcmLevel]. Interleaved
 * channels are treated as one sample stream, so the result is a combined level rather than a
 * per-channel one.
 */
object PcmLevelMeter {
    /** Floor reported for digital silence (all-zero samples) or an empty buffer. */
    const val FLOOR_DBFS = -96f

    private const val BYTES_PER_SAMPLE = 2
    private const val FULL_SCALE = 32_768.0
    private val SILENCE = PcmLevel(rmsDbfs = FLOOR_DBFS, peakDbfs = FLOOR_DBFS)

    /**
     * 16-bit little-endian PCM, interleaved channels. [byteCount] should be even; a trailing odd
     * byte is ignored, and a count larger than [pcm] is clamped to the array. Returns
     * -infinity-clamped to the -96 dBFS floor for silence.
     */
    fun measure(pcm: ByteArray, byteCount: Int): PcmLevel {
        val clamped = byteCount.coerceIn(0, pcm.size)
        val usableBytes = clamped - clamped % BYTES_PER_SAMPLE
        val sampleCount = usableBytes / BYTES_PER_SAMPLE
        if (sampleCount == 0) return SILENCE

        var sumSquares = 0L
        var peak = 0
        var index = 0
        while (index < usableBytes) {
            // Low byte is unsigned; the high byte sign-extends through Byte.toInt().
            val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
            sumSquares += sample.toLong() * sample
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            index += BYTES_PER_SAMPLE
        }
        if (peak == 0) return SILENCE

        val rmsLinear = sqrt(sumSquares.toDouble() / sampleCount) / FULL_SCALE
        return PcmLevel(
            rmsDbfs = toDbfs(rmsLinear),
            peakDbfs = toDbfs(peak / FULL_SCALE),
        )
    }

    private fun toDbfs(linear: Double): Float =
        (20.0 * log10(linear)).toFloat().coerceAtLeast(FLOOR_DBFS)
}
