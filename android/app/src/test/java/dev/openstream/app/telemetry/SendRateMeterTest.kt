package dev.openstream.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendRateMeterTest {
    @Test
    fun computesMegabitsPerSecondFromMonotonicSamples() {
        val meter = SendRateMeter()
        assertNull(meter.sample(totalBytes = 1_000_000, timestampNanos = 1_000_000_000L))

        val bitsPerSecond = meter.sample(
            totalBytes = 3_000_000,
            timestampNanos = 2_000_000_000L,
        )

        assertEquals(16_000_000.0, bitsPerSecond!!, 0.001)
    }

    @Test
    fun counterResetStartsANewWindowInsteadOfReportingNegativeRate() {
        val meter = SendRateMeter()
        meter.sample(totalBytes = 5_000_000, timestampNanos = 1_000_000_000L)

        assertNull(meter.sample(totalBytes = 10, timestampNanos = 2_000_000_000L))
        assertEquals(
            8_000.0,
            meter.sample(totalBytes = 1_010, timestampNanos = 3_000_000_000L)!!,
            0.001,
        )
    }

    @Test
    fun resetForgetsPreviousWindow() {
        val meter = SendRateMeter()
        meter.sample(totalBytes = 100, timestampNanos = 1_000L)
        meter.reset()
        assertNull(meter.sample(totalBytes = 200, timestampNanos = 2_000L))
    }
}
