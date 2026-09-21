package com.synclab.airlens.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SrtLinkStatsTest {
    @Test
    fun lossPercentIsZeroWhenNothingHasBeenSent() {
        assertEquals(0.0, stats(sent = 0, lost = 0).lossPercent, 0.0)
        // A non-zero loss counter with no sent packets must not divide by zero.
        assertEquals(0.0, stats(sent = 0, lost = 5).lossPercent, 0.0)
        assertEquals(0.0, stats(sent = -1, lost = 5).lossPercent, 0.0)
    }

    @Test
    fun lossPercentIsLostOverSentTimesOneHundred() {
        assertEquals(1.2, stats(sent = 1_000, lost = 12).lossPercent, 1e-9)
        assertEquals(0.0, stats(sent = 1_000, lost = 0).lossPercent, 0.0)
        assertEquals(100.0, stats(sent = 250, lost = 250).lossPercent, 1e-9)
    }

    @Test
    fun lossPercentKeepsFractionalPrecision() {
        // Integer arithmetic would truncate these to 0 % / 14 %.
        assertEquals(0.1, stats(sent = 1_000, lost = 1).lossPercent, 1e-9)
        assertEquals(100.0 / 7.0, stats(sent = 7, lost = 1).lossPercent, 1e-9)
        assertEquals(14.29, stats(sent = 7, lost = 1).lossPercent, 0.005)
    }

    @Test
    fun fromNativeSampleMapsFieldsInBridgeOrder() {
        val sample = doubleArrayOf(8.25, 120_000.0, 12.0, 30.0, 11.9)

        val stats = SrtLinkStats.fromNativeSample(sample)!!

        assertEquals(8.25, stats.rttMs, 0.0)
        assertEquals(120_000L, stats.sentPackets)
        assertEquals(12L, stats.lostPackets)
        assertEquals(30L, stats.retransmittedPackets)
        assertEquals(11.9, stats.sendRateMbps, 0.0)
        assertEquals(0.01, stats.lossPercent, 1e-9)
    }

    @Test
    fun fromNativeSampleRejectsShortArrays() {
        assertNull(SrtLinkStats.fromNativeSample(DoubleArray(0)))
        assertNull(SrtLinkStats.fromNativeSample(doubleArrayOf(1.0, 2.0, 3.0, 4.0)))
    }

    private fun stats(sent: Long, lost: Long) = SrtLinkStats(
        rttMs = 0.0,
        sentPackets = sent,
        lostPackets = lost,
        retransmittedPackets = 0,
        sendRateMbps = 0.0,
    )
}
