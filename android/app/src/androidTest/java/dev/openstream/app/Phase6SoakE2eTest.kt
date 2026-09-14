package dev.openstream.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Soak test Phase 6. Test giữ một phiên 4K30 + micro liên tục thay vì lặp các
 * phiên ngắn. Host-side harness chịu trách nhiệm thu MPEG-TS, ffprobe, logcat,
 * bộ nhớ và nhiệt độ trong suốt thời gian chạy.
 */
@RunWith(AndroidJUnit4::class)
class Phase6SoakE2eTest {

    @Test
    fun soak4k30WithMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val receiverHost = requireArgument(args.getString(ARG_RECEIVER_HOST), ARG_RECEIVER_HOST)
        val receiverPort = args.getString(ARG_RECEIVER_PORT)?.toIntOrNull() ?: DEFAULT_RECEIVER_PORT
        val durationSeconds = args.getString(ARG_DURATION_SECONDS)?.toIntOrNull()
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: DEFAULT_DURATION_SECONDS
        val streamBitrateMbps = args.getString(ARG_STREAM_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(MIN_SOAK_BITRATE_MBPS, MAX_SOAK_BITRATE_MBPS)
            ?: DEFAULT_STREAM_BITRATE_MBPS
        val capabilityBitrateMbps = args.getString(ARG_CAPABILITY_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
            ?: DEFAULT_CAPABILITY_BITRATE_MBPS
        val latencyMs = args.getString(ARG_LATENCY_MS)?.toIntOrNull()
            ?.coerceIn(StreamConfigStore.MIN_LATENCY_MS, StreamConfigStore.MAX_LATENCY_MS)
            ?: DEFAULT_LATENCY_MS

        assertTrue(
            "CAMERA permission must be granted before instrumentation starts",
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(
            "RECORD_AUDIO permission must be granted before instrumentation starts",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )

        val capabilityModes = StreamingCapabilityResolver(context)
            .resolve(capabilityBitrateMbps * 1_000_000)
        val targetMode = capabilityModes.firstOrNull {
            it.lens == CameraLens.Back &&
                it.width == TARGET_WIDTH &&
                it.height == TARGET_HEIGHT &&
                it.fps == TARGET_FPS
        }
        assertNotNull(
            "Main back camera + hardware AVC must support 3840x2160@30 at $capabilityBitrateMbps Mbps",
            targetMode,
        )
        targetMode!!

        val networkModes = StreamingCapabilityResolver(context)
            .resolve(streamBitrateMbps * 1_000_000)
        assertTrue(
            "The physical 4K30 path must support soak bitrate $streamBitrateMbps Mbps",
            networkModes.any {
                it.cameraId == targetMode.cameraId &&
                    it.width == TARGET_WIDTH &&
                    it.height == TARGET_HEIGHT &&
                    it.fps == TARGET_FPS
            },
        )

        val config = StreamConfig.Baseline1080p30.copy(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = streamBitrateMbps * 1_000_000,
            keyframeIntervalSeconds = TARGET_KEYFRAME_SECONDS,
            latencyMs = latencyMs,
            codecPreference = CodecPreference.ForceAvc,
            audioEnabled = true,
            audioSampleRate = TARGET_AUDIO_SAMPLE_RATE,
            audioChannelCount = TARGET_AUDIO_CHANNELS,
            audioBitrate = TARGET_AUDIO_BITRATE,
        )
        StreamConfigStore.save(context, config)
        StreamConfig.installRuntimeConfig(config)

        writePreflightEvidence(
            filesDir = context.filesDir,
            cameraId = targetMode.cameraId,
            highProfileAvailable = targetMode.highProfileAvailable,
            capabilityBitrateMbps = capabilityBitrateMbps,
            streamBitrateMbps = streamBitrateMbps,
            durationSeconds = durationSeconds,
            receiverHost = receiverHost,
            receiverPort = receiverPort,
            latencyMs = latencyMs,
        )

        val targetUri = Uri.Builder()
            .scheme("openstream")
            .authority("connect")
            .appendQueryParameter("host", receiverHost)
            .appendQueryParameter("port", receiverPort.toString())
            .appendQueryParameter("latency", latencyMs.toString())
            .appendQueryParameter("bitrateMbps", streamBitrateMbps.toString())
            .appendQueryParameter("name", "Phase 6 soak")
            .build()
        val intent = Intent(Intent.ACTION_VIEW, targetUri, context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()

        assertTrue(
            "OpenStream did not reach LIVE state within ${CONNECT_TIMEOUT_MS}ms",
            waitForLiveState(instrumentation, activity, CONNECT_TIMEOUT_MS),
        )

        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue(
                "OpenStream left LIVE state during Phase 6 soak",
                isLive(instrumentation, activity),
            )
            SystemClock.sleep(LIVE_POLL_MS)
        }

        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun waitForLiveState(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isLive(instrumentation, activity)) return true
            SystemClock.sleep(LIVE_POLL_MS)
        }
        return false
    }

    private fun isLive(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        var live = false
        instrumentation.runOnMainSync {
            live = activity.findViewById<View>(R.id.liveBadge).visibility == View.VISIBLE
        }
        return live
    }

    private fun writePreflightEvidence(
        filesDir: File,
        cameraId: String,
        highProfileAvailable: Boolean,
        capabilityBitrateMbps: Int,
        streamBitrateMbps: Int,
        durationSeconds: Int,
        receiverHost: String,
        receiverPort: Int,
        latencyMs: Int,
    ) {
        val json = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("cameraId", cameraId)
            .put("lens", CameraLens.Back.name)
            .put("width", TARGET_WIDTH)
            .put("height", TARGET_HEIGHT)
            .put("fps", TARGET_FPS)
            .put("capabilityBitrateMbps", capabilityBitrateMbps)
            .put("streamBitrateMbps", streamBitrateMbps)
            .put("highProfileAvailable", highProfileAvailable)
            .put("keyframeIntervalSeconds", TARGET_KEYFRAME_SECONDS)
            .put("audioEnabled", true)
            .put("audioSampleRate", TARGET_AUDIO_SAMPLE_RATE)
            .put("audioChannels", TARGET_AUDIO_CHANNELS)
            .put("audioBitrate", TARGET_AUDIO_BITRATE)
            .put("receiverHost", receiverHost)
            .put("receiverPort", receiverPort)
            .put("latencyMs", latencyMs)
            .put("durationSeconds", durationSeconds)
        File(filesDir, PREFLIGHT_EVIDENCE_FILE).writeText(json.toString(2))
    }

    private fun requireArgument(value: String?, name: String): String {
        assertTrue("Missing instrumentation argument: $name", !value.isNullOrBlank())
        return value!!.trim()
    }

    companion object {
        private const val ARG_RECEIVER_HOST = "receiverHost"
        private const val ARG_RECEIVER_PORT = "receiverPort"
        private const val ARG_DURATION_SECONDS = "durationSeconds"
        private const val ARG_STREAM_BITRATE_MBPS = "streamBitrateMbps"
        private const val ARG_CAPABILITY_BITRATE_MBPS = "capabilityBitrateMbps"
        private const val ARG_LATENCY_MS = "latencyMs"

        private const val TARGET_WIDTH = 3840
        private const val TARGET_HEIGHT = 2160
        private const val TARGET_FPS = 30
        private const val TARGET_KEYFRAME_SECONDS = 2
        private const val TARGET_AUDIO_SAMPLE_RATE = 48_000
        private const val TARGET_AUDIO_CHANNELS = 1
        private const val TARGET_AUDIO_BITRATE = 128_000

        private const val DEFAULT_RECEIVER_PORT = 19060
        private const val DEFAULT_DURATION_SECONDS = 1_800
        private const val MIN_DURATION_SECONDS = 60
        private const val MAX_DURATION_SECONDS = 3_600
        private const val DEFAULT_STREAM_BITRATE_MBPS = 30
        private const val MIN_SOAK_BITRATE_MBPS = 20
        private const val MAX_SOAK_BITRATE_MBPS = 40
        private const val DEFAULT_CAPABILITY_BITRATE_MBPS = 30
        private const val DEFAULT_LATENCY_MS = 2_000
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val LIVE_POLL_MS = 1_000L

        const val PREFLIGHT_EVIDENCE_FILE = "phase6-soak-preflight.json"
    }
}
