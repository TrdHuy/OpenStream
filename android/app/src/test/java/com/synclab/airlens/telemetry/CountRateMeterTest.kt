package com.synclab.airlens.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountRateMeterTest {
    @Test
    fun computesCountsPerSecondFromMonotonicSamples() {
        val meter = CountRateMeter()
        assertNull(meter.sample(totalCount = 1_000, timestampNanos = 1_000_000_000L))

        val perSecond = meter.sample(
            totalCount = 1_030,
            timestampNanos = 2_000_000_000L,
        )

        assertEquals(30.0, perSecond!!, 0.001)
    }

    @Test
    fun scalesToTheElapsedInterval() {
        val meter = CountRateMeter()
        meter.sample(totalCount = 0, timestampNanos = 0L)

        assertEquals(
            29.97,
            meter.sample(totalCount = 2_997, timestampNanos = 100_000_000_000L)!!,
            0.0001,
        )
    }

    @Test
    fun counterResetStartsANewWindowInsteadOfReportingNegativeRate() {
        val meter = CountRateMeter()
        meter.sample(totalCount = 5_000, timestampNanos = 1_000_000_000L)

        assertNull(meter.sample(totalCount = 10, timestampNanos = 2_000_000_000L))
        assertEquals(
            30.0,
            meter.sample(totalCount = 40, timestampNanos = 3_000_000_000L)!!,
            0.001,
        )
    }

    @Test
    fun nonAdvancingClockYieldsNoRate() {
        val meter = CountRateMeter()
        meter.sample(totalCount = 100, timestampNanos = 5_000L)
        assertNull(meter.sample(totalCount = 200, timestampNanos = 5_000L))
        assertNull(meter.sample(totalCount = 300, timestampNanos = 4_000L))
    }

    @Test
    fun resetForgetsPreviousWindow() {
        val meter = CountRateMeter()
        meter.sample(totalCount = 100, timestampNanos = 1_000L)
        meter.reset()
        assertNull(meter.sample(totalCount = 200, timestampNanos = 2_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeCounters() {
        CountRateMeter().sample(totalCount = -1, timestampNanos = 1_000L)
    }
}
