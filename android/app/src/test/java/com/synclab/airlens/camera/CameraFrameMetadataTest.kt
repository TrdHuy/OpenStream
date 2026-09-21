package com.synclab.airlens.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Staged by Agent C for the integrator. Intended location:
 * android/app/src/test/java/com/synclab/airlens/camera/CameraFrameMetadataTest.kt
 */
class CameraFrameMetadataTest {
    private companion object {
        const val MS = 1_000_000L
        const val EPS = 1e-9
    }

    @Test
    fun meterNeedsTwoFramesBeforeReportingAnInterval() {
        val meter = FrameTimingMeter()
        assertNull(meter.frameIntervalMsAvg)
        assertNull(meter.frameJitterMs)

        meter.onFrame(0L)
        assertNull(meter.frameIntervalMsAvg)
        assertNull(meter.frameJitterMs)

        meter.onFrame(33 * MS)
        assertEquals(33.0, meter.frameIntervalMsAvg!!, EPS)
        assertEquals(0.0, meter.frameJitterMs!!, EPS)
    }

    @Test
    fun steadyCadenceKeepsIntervalFlatAndJitterAtZero() {
        val meter = FrameTimingMeter()
        for (i in 0..10) meter.onFrame(i * 33 * MS)

        assertEquals(33.0, meter.frameIntervalMsAvg!!, EPS)
        assertEquals(0.0, meter.frameJitterMs!!, EPS)
    }

    @Test
    fun droppedFrameMovesIntervalAndJitterByAlpha() {
        val meter = FrameTimingMeter(alpha = 0.1)
        meter.onFrame(0L)
        meter.onFrame(33 * MS)
        meter.onFrame(66 * MS)
        // One 66 ms gap (a dropped frame) against a 33 ms average.
        meter.onFrame(132 * MS)

        assertEquals(36.3, meter.frameIntervalMsAvg!!, EPS)
        assertEquals(3.3, meter.frameJitterMs!!, EPS)
    }

    @Test
    fun nonPositiveDeltasAreIgnoredButRebaseTheClock() {
        val meter = FrameTimingMeter()
        meter.onFrame(0L)
        meter.onFrame(33 * MS)
        // Duplicate timestamp, then a timestamp that went backwards.
        meter.onFrame(33 * MS)
        meter.onFrame(20 * MS)

        assertEquals(33.0, meter.frameIntervalMsAvg!!, EPS)
        assertEquals(0.0, meter.frameJitterMs!!, EPS)

        // The rewound timestamp becomes the new baseline.
        meter.onFrame(53 * MS)
        assertEquals(33.0, meter.frameIntervalMsAvg!!, EPS)
    }

    @Test
    fun resetForgetsEverything() {
        val meter = FrameTimingMeter()
        meter.onFrame(0L)
        meter.onFrame(33 * MS)
        meter.reset()

        assertNull(meter.frameIntervalMsAvg)
        assertNull(meter.frameJitterMs)
        meter.onFrame(1_000 * MS)
        assertNull(meter.frameIntervalMsAvg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAlphaOutsideUnitInterval() {
        FrameTimingMeter(alpha = 0.0)
    }

    @Test
    fun focalLengthUsesFullFrameDiagonalOverSensorDiagonal() {
        // 6.4 x 4.8 mm sensor has an 8.0 mm diagonal: 43.27 / 8 * 5.4 = 29.2 -> 29.
        assertEquals(29, focalLength35mmEquivalent(5.4f, 6.4f, 4.8f))
        // Full-frame sensor maps a lens onto itself.
        assertEquals(50, focalLength35mmEquivalent(50f, 36f, 24f))
    }

    @Test
    fun focalLengthIsNullForUnknownOrDegenerateInputs() {
        assertNull(focalLength35mmEquivalent(5.4f, 0f, 4.8f))
        assertNull(focalLength35mmEquivalent(5.4f, 6.4f, -1f))
        assertNull(focalLength35mmEquivalent(0f, 6.4f, 4.8f))
        assertNull(focalLength35mmEquivalent(Float.NaN, 6.4f, 4.8f))
        assertNull(focalLength35mmEquivalent(5.4f, Float.POSITIVE_INFINITY, 4.8f))
    }
}
