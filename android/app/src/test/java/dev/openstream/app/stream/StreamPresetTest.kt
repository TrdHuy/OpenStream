package dev.openstream.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresetTest {
    @Test
    fun catalogContainsDailyUsePresetsWithStableDefaults() {
        assertEquals(listOf("1080p30", "1080p60", "4K30", "4K60"), StreamPreset.entries.map { it.displayName })
        assertEquals(12, StreamPreset.FullHd30.bitrateMbps)
        assertEquals(20, StreamPreset.FullHd60.bitrateMbps)
        assertEquals(30, StreamPreset.UltraHd30.bitrateMbps)
        assertEquals(35, StreamPreset.UltraHd60.bitrateMbps)
        assertTrue(StreamPreset.entries.all { it.keyframeIntervalSeconds == 2 })
    }

    @Test
    fun presetAppliesResolutionFpsBitrateAndKeyframeWithoutDroppingAudioSettings() {
        val base = StreamConfig.Baseline1080p30.copy(
            audioEnabled = false,
            audioSampleRate = 44_100,
            audioChannelCount = 2,
            audioBitrate = 192_000,
        )
        val result = StreamPreset.UltraHd30.applyTo(base)
        assertEquals(3840, result.width)
        assertEquals(2160, result.height)
        assertEquals(30, result.fps)
        assertEquals(30_000_000, result.bitrate)
        assertEquals(2, result.keyframeIntervalSeconds)
        assertEquals(false, result.audioEnabled)
        assertEquals(44_100, result.audioSampleRate)
        assertEquals(2, result.audioChannelCount)
        assertEquals(192_000, result.audioBitrate)
    }
}
