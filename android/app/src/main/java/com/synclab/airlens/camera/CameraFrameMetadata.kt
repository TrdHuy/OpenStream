package com.synclab.airlens.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Throttled snapshot of Camera2 per-frame metadata published by [Camera2Controller].
 *
 * Every field except [timestampNs] is nullable: a `null` means the HAL did not report
 * the key for this frame (or the static characteristic was unavailable) and the HUD
 * must render "—" rather than a fabricated value. Raw `CaptureResult.CONTROL_*_STATE`
 * integers are passed through untouched so the presentation layer owns the wording.
 *
 * Instances are immutable and safe to hand across threads; the controller replaces
 * the whole snapshot at most every ~250 ms, so consumers must not expect one per frame.
 */
data class CameraFrameMetadata(
    /** `CaptureResult.SENSOR_TIMESTAMP` of the frame this snapshot was built from (ns). */
    val timestampNs: Long,
    val cameraId: String?,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    /** From `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]` + `SENSOR_INFO_PHYSICAL_SIZE` diagonal; null if unknown. */
    val focalLength35mmEq: Int?,
    /** `CaptureResult.CONTROL_AF_STATE_*` raw ints. */
    val afState: Int?,
    val aeState: Int?,
    val awbState: Int?,
    val focusDistanceDiopters: Float?,
    val zoomRatio: Float?,
    val evCompensationSteps: Int?,
    val evStepNumerator: Int?,
    val evStepDenominator: Int?,
    /** EWMA of consecutive `SENSOR_TIMESTAMP` deltas, in ms. */
    val frameIntervalMsAvg: Double?,
    /** EWMA of `|delta - frameIntervalMsAvg|`, in ms. */
    val frameJitterMs: Double?,
)

/**
 * O(1) exponentially-weighted tracker of sensor frame interval and jitter.
 *
 * Feed it every `SENSOR_TIMESTAMP` in arrival order. The first frame only seeds the
 * previous timestamp; the second frame seeds the average; from then on both the
 * interval and the jitter move by [alpha] towards each new observation. Non-positive
 * deltas (duplicate or out-of-order timestamps) are ignored so a single HAL hiccup
 * cannot poison the average.
 *
 * Not thread-safe: the controller drives it from the single camera handler thread.
 */
internal class FrameTimingMeter(
    private val alpha: Double = DEFAULT_ALPHA,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1]" }
    }

    private var lastTimestampNs: Long? = null
    private var intervalMsAvg: Double? = null
    private var jitterMsAvg: Double? = null

    /** Smoothed frame interval in ms, or null until two frames have been observed. */
    val frameIntervalMsAvg: Double? get() = intervalMsAvg

    /** Smoothed absolute deviation from [frameIntervalMsAvg] in ms, or null until seeded. */
    val frameJitterMs: Double? get() = jitterMsAvg

    fun onFrame(timestampNs: Long) {
        val previous = lastTimestampNs
        lastTimestampNs = timestampNs
        if (previous == null) return
        val deltaMs = (timestampNs - previous) / NANOS_PER_MILLI
        if (deltaMs <= 0.0) return

        val avg = intervalMsAvg
        if (avg == null) {
            intervalMsAvg = deltaMs
            jitterMsAvg = 0.0
            return
        }
        val deviation = abs(deltaMs - avg)
        intervalMsAvg = avg + alpha * (deltaMs - avg)
        val jitter = jitterMsAvg ?: 0.0
        jitterMsAvg = jitter + alpha * (deviation - jitter)
    }

    fun reset() {
        lastTimestampNs = null
        intervalMsAvg = null
        jitterMsAvg = null
    }

    companion object {
        const val DEFAULT_ALPHA = 0.1
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/**
 * 35 mm-equivalent focal length: `43.27 mm / sensor diagonal × focal`, rounded.
 *
 * Returns null when any input is non-positive or non-finite, which is how some HALs
 * report an unknown physical sensor size.
 */
internal fun focalLength35mmEquivalent(
    focalLengthMm: Float,
    sensorWidthMm: Float,
    sensorHeightMm: Float,
): Int? {
    if (!focalLengthMm.isFinite() || !sensorWidthMm.isFinite() || !sensorHeightMm.isFinite()) return null
    if (focalLengthMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
    val diagonalMm = sqrt(
        sensorWidthMm.toDouble() * sensorWidthMm + sensorHeightMm.toDouble() * sensorHeightMm,
    )
    if (diagonalMm <= 0.0) return null
    val equivalent = FULL_FRAME_DIAGONAL_MM / diagonalMm * focalLengthMm
    if (!equivalent.isFinite()) return null
    return equivalent.roundToInt()
}

/** Diagonal of a 36 × 24 mm full-frame sensor. */
private const val FULL_FRAME_DIAGONAL_MM = 43.27
