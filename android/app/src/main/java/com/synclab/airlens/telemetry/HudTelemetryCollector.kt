package com.synclab.airlens.telemetry

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.synclab.airlens.R
import com.synclab.airlens.camera.CameraFrameMetadata
import com.synclab.airlens.camera.CameraLens
import com.synclab.airlens.encoder.PcmLevel
import com.synclab.airlens.stream.SrtLinkStats
import com.synclab.airlens.stream.StreamStats
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything the collector reads on each tick.
 *
 * All providers are cheap (a few volatile reads or an atomic snapshot), thread-safe and may
 * return null while their source is idle. They are invoked on the collector's own thread,
 * never on the UI thread.
 */
data class CollectorInputs(
    val streamStats: () -> StreamStats,
    val linkStats: () -> SrtLinkStats?,
    val frameMetadata: () -> CameraFrameMetadata?,
    val audioLevel: () -> PcmLevel?,
    val micState: () -> MicState,
    val streamContext: () -> StreamContext,
)

/**
 * Slow-changing session facts owned by MainActivity. The collector only reads them; it never
 * owns or drives a media session. [liveSinceElapsedMs] is [SystemClock.elapsedRealtime] at
 * the moment the transport went live, or null when not live.
 */
data class StreamContext(
    val live: Boolean,
    val liveSinceElapsedMs: Long?,
    val targetBitrateMbps: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val keyframeIntervalSeconds: Int,
    val codecLabel: String,
    val lens: CameraLens,
    val zoom: Float,
    val targetUrl: String,
    val micSummary: String,
    val keepScreenOn: Boolean,
    /** "streaming" | "stalled" | "disconnected" | "idle" */
    val encoderState: String,
)

/**
 * Samples stream, camera, network and battery telemetry once per second on a dedicated
 * background thread ("AirLensTelemetry") and hands ONE [HudSnapshot] per tick to
 * [onSnapshot] on the main thread.
 *
 * Threading and queue policy:
 * - All sampling (binder reads, provider lambdas, rate meters) runs on the collector thread.
 *   The main thread only receives the finished snapshot; the collector never touches views.
 * - Delivery keeps only the latest snapshot: capacity 1, overflow policy = replace. The
 *   pending main-thread delivery is cancelled before the next one is posted, so a busy UI
 *   thread can never build up a backlog.
 * - [start] and [stop] are idempotent and may be called from any thread. [stop] quits the
 *   thread safely and drops any snapshot that has not been delivered yet.
 */
class HudTelemetryCollector(
    context: Context,
    private val inputs: CollectorInputs,
    private val onSnapshot: (HudSnapshot) -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val sampler = TelemetrySampler(appContext)
    private val strings: HudStrings = ResourceStrings(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceModel = deviceModelLabel()
    private val lifecycleLock = Any()

    @Volatile
    private var session: Session? = null

    /**
     * [SystemClock.elapsedRealtime] of the last tick that observed new video access units.
     * Survives [stop]/[start] so a Lost state can still report "last frame N s ago".
     */
    @Volatile
    var lastFrameAtElapsedMs: Long? = null
        private set

    fun start() {
        synchronized(lifecycleLock) {
            if (session != null) return
            // Publish before the first tick is posted: run() bails out (and stops rescheduling)
            // whenever it does not see itself as the current session.
            val created = Session()
            session = created
            created.begin()
        }
    }

    fun stop() {
        val ended = synchronized(lifecycleLock) {
            val current = session ?: return
            session = null
            current
        }
        ended.end()
    }

    private fun lensSubLabel(lens: CameraLens): String = strings.get(
        when (lens) {
            CameraLens.BackUltrawide -> R.string.cam_lens_ultra
            CameraLens.Back -> R.string.cam_lens_main
            CameraLens.BackTelephoto -> R.string.cam_lens_tele
            CameraLens.Front -> R.string.cam_lens_front
        },
    )

    private fun lensNameLabel(lens: CameraLens): String = strings.get(
        when (lens) {
            CameraLens.BackUltrawide -> R.string.cam_lens_name_ultra
            CameraLens.Back -> R.string.cam_lens_name_main
            CameraLens.BackTelephoto -> R.string.cam_lens_name_tele
            CameraLens.Front -> R.string.cam_lens_name_front
        },
    )

    /** "Samsung SM-S916B" — manufacturer title-cased once, model as reported. */
    private fun deviceModelLabel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        return "$manufacturer ${Build.MODEL.orEmpty()}".trim()
    }

    /**
     * One start()..stop() lifetime: its own thread, rate meters and single delivery slot.
     * A session that has been ended can still be mid-tick on its thread; every hand-off
     * checks `session === this` so it can never deliver into a newer session.
     */
    private inner class Session : Runnable {
        private val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        private lateinit var handler: Handler

        // Touched only on the collector thread.
        private val bitrateMeter = SendRateMeter()
        private val fpsMeter = CountRateMeter()
        private var previousAccessUnitsSent: Long? = null
        private var nextTickUptimeMs = 0L
        private var failures = 0L

        // Latest-only hand-off to the main thread (capacity 1, replace on overflow).
        private val pending = AtomicReference<HudSnapshot?>(null)
        private val deliver = Runnable {
            val snapshot = pending.getAndSet(null) ?: return@Runnable
            if (session === this@Session) onSnapshot(snapshot)
        }

        fun begin() {
            thread.start()
            handler = Handler(thread.looper)
            nextTickUptimeMs = SystemClock.uptimeMillis()
            handler.post(this)
        }

        fun end() {
            handler.removeCallbacks(this)
            thread.quitSafely()
            mainHandler.removeCallbacks(deliver)
            pending.set(null)
        }

        override fun run() {
            if (session !== this) return
            try {
                pending.set(collect())
                mainHandler.removeCallbacks(deliver)
                mainHandler.post(deliver)
            } catch (e: RuntimeException) {
                // A provider or platform hiccup must never take the stream down with it;
                // the HUD simply keeps its previous snapshot until the next good tick.
                failures += 1
                if (failures == 1L || failures % FAILURE_LOG_EVERY == 0L) {
                    Log.w(TAG, "Telemetry tick failed ($failures so far)", e)
                }
            }
            scheduleNext()
        }

        /** Fixed 1 Hz cadence; a slow tick skips ahead instead of bunching up. */
        private fun scheduleNext() {
            val now = SystemClock.uptimeMillis()
            nextTickUptimeMs += SAMPLE_PERIOD_MS
            if (nextTickUptimeMs <= now) nextTickUptimeMs = now + SAMPLE_PERIOD_MS
            handler.postAtTime(this, nextTickUptimeMs)
        }

        private fun collect(): HudSnapshot {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val nowNanos = System.nanoTime()
            val ctx = inputs.streamContext()
            val stats = inputs.streamStats()
            val link = inputs.linkStats()
            val frame = inputs.frameMetadata()
            val audio = inputs.audioLevel()
            val mic = inputs.micState()
            val network = sampler.sampleNetwork()
            val battery = sampler.sampleBattery()

            val actualBitrateMbps: Double?
            val actualFps: Double?
            if (ctx.live) {
                actualBitrateMbps = bitrateMeter.sample(stats.lifetimeBytesSent, nowNanos)?.div(1_000_000.0)
                actualFps = fpsMeter.sample(stats.accessUnitsSent, nowNanos)
            } else {
                bitrateMeter.reset()
                fpsMeter.reset()
                actualBitrateMbps = null
                actualFps = null
            }

            // "Increased" also covers a per-session counter that restarted from zero and grew
            // again within one tick. The first tick of a session only records the baseline.
            val previousAu = previousAccessUnitsSent
            val currentAu = stats.accessUnitsSent
            if (previousAu != null && currentAu != previousAu && currentAu > 0L) {
                lastFrameAtElapsedMs = nowElapsedMs
            }
            previousAccessUnitsSent = currentAu

            val durationSeconds = ctx.liveSinceElapsedMs
                ?.let { (nowElapsedMs - it).coerceAtLeast(0L) / 1_000L }
                ?: 0L

            return HudSnapshot(
                live = ctx.live,
                targetBitrateMbps = ctx.targetBitrateMbps,
                actualBitrateMbps = actualBitrateMbps,
                targetFps = ctx.fps,
                actualFps = actualFps,
                width = ctx.width,
                height = ctx.height,
                codecLabel = ctx.codecLabel,
                durationSeconds = durationSeconds,
                videoFrames = stats.accessUnitsSent,
                keyframes = stats.keyframesSent,
                keyframeIntervalSeconds = ctx.keyframeIntervalSeconds,
                audioFrames = stats.audioAccessUnitsSent,
                sessionMegabits = stats.totalSessionBytesSent * 8.0 / 1_000_000.0,
                encoderState = ctx.encoderState,
                lensSub = lensSubLabel(ctx.lens),
                lensName = lensNameLabel(ctx.lens),
                cameraId = frame?.cameraId,
                zoom = frame?.zoomRatio ?: ctx.zoom,
                iso = frame?.iso,
                exposureTimeNs = frame?.exposureTimeNs,
                aperture = frame?.aperture,
                afState = frame?.afState,
                aeState = frame?.aeState,
                awbState = frame?.awbState,
                focusDistanceDiopters = frame?.focusDistanceDiopters,
                evSteps = frame?.evCompensationSteps,
                evStepNumerator = frame?.evStepNumerator,
                evStepDenominator = frame?.evStepDenominator,
                focalLength35mmEq = frame?.focalLength35mmEq,
                frameIntervalMsAvg = frame?.frameIntervalMsAvg,
                frameJitterMs = frame?.frameJitterMs,
                wifiRssi = network.wifiRssi,
                wifiBandLabel = wifiBandLabel(network.frequencyMhz, strings),
                networkTypeLabel = networkTypeLabel(network, strings),
                srtRttMs = link?.rttMs,
                srtLossPercent = link?.lossPercent,
                reconnects = stats.reconnects,
                connectionLosses = stats.connectionLosses,
                targetUrl = ctx.targetUrl,
                batteryPercent = battery.percent,
                batteryCharging = battery.charging,
                batteryMinutesRemaining = battery.minutesRemaining,
                temperatureC = battery.temperatureC,
                thermalStatus = battery.thermalStatus,
                micState = mic,
                micSummary = ctx.micSummary,
                audioRmsDbfs = audio?.rmsDbfs,
                audioPeakDbfs = audio?.peakDbfs,
                keepScreenOn = ctx.keepScreenOn,
                deviceModel = deviceModel,
            )
        }
    }

    /** Resource-backed [HudStrings]; `Resources` reads are thread-safe, so this works off-main. */
    private class ResourceStrings(private val context: Context) : HudStrings {
        override fun get(resId: Int): String = context.getString(resId)

        override fun get(resId: Int, vararg args: Any): String = context.getString(resId, *args)
    }

    private companion object {
        const val TAG = "HudTelemetryCollector"
        const val THREAD_NAME = "AirLensTelemetry"
        const val SAMPLE_PERIOD_MS = 1_000L
        const val FAILURE_LOG_EVERY = 60L
    }
}

/**
 * Human label for the active data path: "Wi-Fi 6 · 5 GHz", "Wi-Fi · 2.4 GHz", "Wi-Fi",
 * "Cellular", "Ethernet" or "Offline". The Wi-Fi generation comes from
 * [NetworkTelemetry.wifiStandard] (6 → Wi-Fi 6, 5 → Wi-Fi 5, 4 → Wi-Fi 4, anything else →
 * plain "Wi-Fi"); the band comes from [wifiBandLabel].
 */
fun networkTypeLabel(n: NetworkTelemetry, strings: HudStrings): String = when (n.transport) {
    NetworkTransport.Wifi -> {
        val base = strings
            .get(R.string.hud_f_network_wifi, wifiGenerationLabel(n.wifiStandard).orEmpty())
            .trim()
        val band = wifiBandLabel(n.frequencyMhz, strings)
        if (band == null) base else "$base · $band"
    }
    NetworkTransport.Cellular -> strings.get(R.string.hud_v_cellular)
    NetworkTransport.Ethernet -> strings.get(R.string.hud_v_ethernet)
    NetworkTransport.Offline -> strings.get(R.string.hud_v_offline)
}

/**
 * "2.4 GHz" / "5 GHz" / "6 GHz" from a channel centre frequency in MHz (< 3000 → 2.4,
 * < 5900 → 5, else 6), or null when the frequency is unknown.
 */
fun wifiBandLabel(frequencyMhz: Int?, strings: HudStrings): String? {
    val band = when {
        frequencyMhz == null || frequencyMhz <= 0 -> return null
        frequencyMhz < 3_000 -> "2.4"
        frequencyMhz < 5_900 -> "5"
        else -> "6"
    }
    return strings.get(R.string.hud_f_band_ghz, band)
}

/** Marketing generation digit for a raw `WifiInfo.getWifiStandard()` value, or null. */
private fun wifiGenerationLabel(wifiStandard: Int?): String? = when (wifiStandard) {
    6 -> "6" // ScanResult.WIFI_STANDARD_11AX
    5 -> "5" // ScanResult.WIFI_STANDARD_11AC
    4 -> "4" // ScanResult.WIFI_STANDARD_11N
    else -> null
}
