package com.synclab.airlens.telemetry

/**
 * Colour tone of a HUD value. Colour only appears when a value is out of range;
 * [Neutral] renders as white 92 % (`cam_white_92`).
 */
enum class HudTone { Neutral, Ok, Warn, Err }

/** Tabs of the expanded telemetry sheet, in display order. */
enum class HudTab { Stream, Camera, Network, Device }

/**
 * One rendered row of the HUD (glance card or telemetry sheet).
 *
 * @property key localised label (left column).
 * @property value formatted value (right column); "—" when unknown.
 * @property note optional secondary line under the key.
 * @property tone colour of the value.
 * @property debug true for diagnostic rows that are hidden unless Debug is on.
 */
data class HudRow(
    val key: String,
    val value: String,
    val note: String? = null,
    val tone: HudTone = HudTone.Neutral,
    val debug: Boolean = false,
)

/** Microphone capture state as seen by the HUD. */
enum class MicState { Active, Off, NoPermission }

/**
 * Immutable, fully-sampled telemetry frame produced once per second by the collector
 * (off the UI thread) and rendered by [HudFormatter]. Nullable fields are "unknown"
 * (not live, sensor not reporting, native stats unavailable) and render as "—".
 */
data class HudSnapshot(
    // stream
    val live: Boolean,
    val targetBitrateMbps: Int,
    val actualBitrateMbps: Double?,
    val targetFps: Int,
    val actualFps: Double?,
    val width: Int,
    val height: Int,
    val codecLabel: String,
    val durationSeconds: Long,
    val videoFrames: Long,
    val keyframes: Long,
    val keyframeIntervalSeconds: Int,
    val audioFrames: Long,
    val sessionMegabits: Double,
    /** One of "streaming" | "stalled" | "disconnected" | "idle". */
    val encoderState: String,
    // camera
    /** Upper-case chip sub-label, e.g. "MAIN". */
    val lensSub: String,
    /** Human lens name for the status pill, e.g. "Back main". */
    val lensName: String,
    val cameraId: String?,
    val zoom: Float,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    /** Raw `CaptureResult.CONTROL_AF_STATE_*` value. */
    val afState: Int?,
    /** Raw `CaptureResult.CONTROL_AE_STATE_*` value. */
    val aeState: Int?,
    /** Raw `CaptureResult.CONTROL_AWB_STATE_*` value. */
    val awbState: Int?,
    val focusDistanceDiopters: Float?,
    val evSteps: Int?,
    val evStepNumerator: Int?,
    val evStepDenominator: Int?,
    val focalLength35mmEq: Int?,
    val frameIntervalMsAvg: Double?,
    val frameJitterMs: Double?,
    // network
    val wifiRssi: Int?,
    /** e.g. "5 GHz"; null when not on Wi-Fi or unknown. */
    val wifiBandLabel: String?,
    /** e.g. "Wi-Fi 6 · 5 GHz" | "Cellular" | "Ethernet" | "Offline". */
    val networkTypeLabel: String,
    val srtRttMs: Double?,
    val srtLossPercent: Double?,
    val reconnects: Long,
    val connectionLosses: Long,
    val targetUrl: String,
    // device
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val batteryMinutesRemaining: Int?,
    val temperatureC: Float?,
    /** Raw `PowerManager.THERMAL_STATUS_*` value. */
    val thermalStatus: Int?,
    val micState: MicState,
    /** e.g. "48 kHz mono". */
    val micSummary: String,
    val audioRmsDbfs: Float?,
    val audioPeakDbfs: Float?,
    val keepScreenOn: Boolean,
    val deviceModel: String,
)

/**
 * Resource lookup seam so [HudFormatter] and the UI model stay pure Kotlin and testable
 * on the JVM. Production wraps `Context.getString`; tests substitute a fake.
 */
interface HudStrings {
    fun get(resId: Int): String

    fun get(resId: Int, vararg args: Any): String
}
