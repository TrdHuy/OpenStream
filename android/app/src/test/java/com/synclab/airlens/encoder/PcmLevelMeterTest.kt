package com.synclab.airlens.encoder

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class PcmLevelMeterTest {
    @Test
    fun silenceReportsTheFloorForRmsAndPeak() {
        val pcm = ByteArray(SAMPLES_PER_20MS_MONO * 2)

        val level = PcmLevelMeter.measure(pcm, pcm.size)

        assertEquals(-96f, level.rmsDbfs, 0f)
        assertEquals(-96f, level.peakDbfs, 0f)
    }

    @Test
    fun emptyBufferReportsTheFloor() {
        val level = PcmLevelMeter.measure(ByteArray(0), 0)

        assertEquals(-96f, level.rmsDbfs, 0f)
        assertEquals(-96f, level.peakDbfs, 0f)
    }

    @Test
    fun fullScaleSquareWaveIsZeroDbfs() {
        val samples = IntArray(SAMPLES_PER_20MS_MONO) { if (it % 2 == 0) 32_767 else -32_768 }
        val pcm = pcm16LittleEndian(samples)

        val level = PcmLevelMeter.measure(pcm, pcm.size)

        assertEquals(0f, level.peakDbfs, 0.01f)
        assertEquals(0f, level.rmsDbfs, 0.01f)
    }

    @Test
    fun halfScaleSineHasExpectedPeakAndRms() {
        // 1 kHz at 48 kHz = 48 samples per cycle, so 480 samples hold exactly 10 cycles.
        val samples = IntArray(480) { (16_384.0 * sin(2.0 * PI * it / 48.0)).roundToInt() }
        val pcm = pcm16LittleEndian(samples)

        val level = PcmLevelMeter.measure(pcm, pcm.size)

        assertEquals(-6.02f, level.peakDbfs, 0.2f)
        assertEquals(-9.03f, level.rmsDbfs, 0.2f)
    }

    @Test
    fun byteCountSmallerThanArrayLimitsTheMeasuredRegion() {
        // First 480 samples are silent, the rest are full scale. Only the silent prefix is measured.
        val samples = IntArray(SAMPLES_PER_20MS_MONO) { if (it < 480) 0 else 32_767 }
        val pcm = pcm16LittleEndian(samples)

        val level = PcmLevelMeter.measure(pcm, 480 * 2)

        assertEquals(-96f, level.rmsDbfs, 0f)
        assertEquals(-96f, level.peakDbfs, 0f)
    }

    @Test
    fun oddByteCountIgnoresTheTrailingByte() {
        // One silent sample followed by a stray high byte that would read as a loud sample.
        val pcm = byteArrayOf(0x00, 0x00, 0x7F)

        val odd = PcmLevelMeter.measure(pcm, 3)
        val even = PcmLevelMeter.measure(pcm, 2)

        assertEquals(-96f, odd.rmsDbfs, 0f)
        assertEquals(-96f, odd.peakDbfs, 0f)
        assertEquals(even, odd)
    }

    @Test
    fun byteCountLargerThanArrayIsClampedToTheArray() {
        val pcm = pcm16LittleEndian(intArrayOf(16_384, -16_384))

        val level = PcmLevelMeter.measure(pcm, pcm.size + 10)

        assertEquals(-6.02f, level.peakDbfs, 0.01f)
        assertEquals(-6.02f, level.rmsDbfs, 0.01f)
    }

    @Test
    fun negativeSamplesAreDecodedLittleEndianWithSign() {
        // -32768 is 0x8000, stored little-endian as 0x00 0x80.
        val pcm = byteArrayOf(0x00, 0x80.toByte())

        val level = PcmLevelMeter.measure(pcm, pcm.size)

        assertEquals(0f, level.peakDbfs, 0.001f)
        assertEquals(0f, level.rmsDbfs, 0.001f)
    }

    @Test
    fun quietSignalIsNotClampedAboveTheFloor() {
        // A single LSB tone: 20·log10(1/32768) ≈ -90.3 dBFS, which is louder than the floor.
        val samples = IntArray(SAMPLES_PER_20MS_MONO) { if (it % 2 == 0) 1 else -1 }
        val pcm = pcm16LittleEndian(samples)

        val level = PcmLevelMeter.measure(pcm, pcm.size)

        assertEquals(-90.31f, level.peakDbfs, 0.01f)
        assertEquals(-90.31f, level.rmsDbfs, 0.01f)
    }

    private fun pcm16LittleEndian(samples: IntArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        /** 48 kHz mono, 20 ms — the capture buffer size MediaCodecAudioEncoder reads per loop. */
        const val SAMPLES_PER_20MS_MONO = 960
    }
}
