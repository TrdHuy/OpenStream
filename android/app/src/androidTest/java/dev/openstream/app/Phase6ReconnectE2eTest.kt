package dev.openstream.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.StreamConfigStore
import dev.openstream.app.stream.StreamingCapabilityResolver
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Functional reconnect acceptance for Phase 6.
 *
 * The host harness deliberately stops the SRT receiver after media is flowing,
 * leaves it unavailable long enough for caller failure/backoff to be visible,
 * and then starts the listener again. This test verifies the production UI
 * lifecycle actually leaves LIVE and returns to LIVE without relaunching the
 * activity. It is functional evidence only; it is not a Wi-Fi roaming or
 * production-throughput acceptance test.
 */
@RunWith(AndroidJUnit4::class)
class Phase6ReconnectE2eTest {

    @Test
    fun reconnect4k30WithMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val receiverHost = requireArgument(args.getString(ARG_RECEIVER_HOST), ARG_RECEIVER_HOST)
        val receiverPort = args.getString(ARG_RECEIVER_PORT)?.toIntOrNull() ?: DEFAULT_RECEIVER_PORT
        val durationSeconds = args.getString(ARG_DURATION_SECONDS)?.toIntOrNull()
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: DEFAULT_DURATION_SECONDS
        val streamBitrateMbps = args.getString(ARG_STREAM_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, MAX_FUNCTIONAL_BITRATE_MBPS)
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

        val targetMode = StreamingCapabilityResolver(context)
            .resolve(capabilityBitrateMbps * 1_000_000)
            .firstOrNull {
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
            "The physical 4K30 path must support functional bitrate $streamBitrateMbps Mbps",
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

        val targetUri = Uri.Builder()
            .scheme("openstream")
            .authority("connect")
            .appendQueryParameter("host", receiverHost)
            .appendQueryParameter("port", receiverPort.toString())
            .appendQueryParameter("latency", latencyMs.toString())
            .appendQueryParameter("bitrateMbps", streamBitrateMbps.toString())
            .appendQueryParameter("name", "Phase 6 reconnect")
            .build()

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(activityIntent) as MainActivity
        instrumentation.waitForIdleSync()
        SystemClock.sleep(PREVIEW_WARMUP_MS)

        val connectIntent = Intent(Intent.ACTION_VIEW, targetUri, context, MainActivity::class.java)
        instrumentation.runOnMainSync {
            instrumentation.callActivityOnNewIntent(activity, connectIntent)
        }
        instrumentation.waitForIdleSync()

        assertTrue(
            "OpenStream did not reach initial LIVE state within ${CONNECT_TIMEOUT_MS}ms",
            waitForLiveState(instrumentation, activity, CONNECT_TIMEOUT_MS),
        )

        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
        var interruptionObserved = false
        var recoveryObserved = false
        var recoveryStartedAt = 0L

        while (SystemClock.elapsedRealtime() < deadline) {
            val live = isLive(instrumentation, activity)
            when {
                !interruptionObserved && !live -> {
                    interruptionObserved = true
                }
                interruptionObserved && !recoveryObserved && live -> {
                    recoveryObserved = true
                    recoveryStartedAt = SystemClock.elapsedRealtime()
                }
                recoveryObserved && !live -> {
                    throw AssertionError("OpenStream left LIVE state again after reconnect recovery")
                }
                recoveryObserved &&
                    SystemClock.elapsedRealtime() - recoveryStartedAt >= POST_RECOVERY_STABLE_MS -> {
                    break
                }
            }
            SystemClock.sleep(RECONNECT_POLL_MS)
        }

        assertTrue(
            "Phase 6 reconnect test did not observe receiver interruption before timeout",
            interruptionObserved,
        )
        assertTrue(
            "OpenStream did not recover LIVE state after receiver returned",
            recoveryObserved,
        )
        assertTrue(
            "OpenStream was not LIVE at the end of reconnect validation",
            isLive(instrumentation, activity),
        )

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
            SystemClock.sleep(RECONNECT_POLL_MS)
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

        private const val DEFAULT_RECEIVER_PORT = 19061
        private const val DEFAULT_DURATION_SECONDS = 90
        private const val MIN_DURATION_SECONDS = 45
        private const val MAX_DURATION_SECONDS = 180
        private const val DEFAULT_STREAM_BITRATE_MBPS = 8
        private const val MAX_FUNCTIONAL_BITRATE_MBPS = 19
        private const val DEFAULT_CAPABILITY_BITRATE_MBPS = 30
        private const val DEFAULT_LATENCY_MS = 120
        private const val PREVIEW_WARMUP_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val RECONNECT_POLL_MS = 250L
        private const val POST_RECOVERY_STABLE_MS = 12_000L
    }
}
