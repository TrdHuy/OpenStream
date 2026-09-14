package dev.openstream.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openstream.app.camera.CameraCapabilityProbe
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.HardwareVideoCapabilityProbe
import dev.openstream.app.encoder.VideoBitrateMode
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
 * Nghiệm thu Giai đoạn 4 trên thiết bị thật.
 *
 * Đường chuẩn vẫn yêu cầu resolver Camera2 + MediaCodec quảng cáo 4K60. Riêng
 * thiết bị có output 4K + AE 60 nhưng getOutputMinFrameDuration() làm probe tĩnh
 * loại 60 fps, test được phép thử một phiên Camera2 4K60 thật. Chỉ ffprobe gần
 * 60 fps ở đầu nhận mới được coi là bằng chứng positive; không có silent fallback.
 */
@RunWith(AndroidJUnit4::class)
class Phase4DeviceE2eTest {

    @Test
    fun validate4k60CapabilityAndStreamWhenSupported() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        val receiverHost = requireArgument(args.getString(ARG_RECEIVER_HOST), ARG_RECEIVER_HOST)
        val receiverPort = args.getString(ARG_RECEIVER_PORT)?.toIntOrNull() ?: DEFAULT_RECEIVER_PORT
        val durationSeconds = args.getString(ARG_DURATION_SECONDS)?.toIntOrNull()
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: DEFAULT_DURATION_SECONDS
        val streamBitrateMbps = args.getString(ARG_STREAM_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS, StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS)
            ?: DEFAULT_STREAM_BITRATE_MBPS
        val capabilityBitrateMbps = args.getString(ARG_CAPABILITY_BITRATE_MBPS)?.toIntOrNull()
            ?.coerceIn(StreamConfig.MIN_CONFIGURABLE_BITRATE_MBPS, StreamConfig.MAX_CONFIGURABLE_BITRATE_MBPS)
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

        val cameraCapabilities = CameraCapabilityProbe(context).query()
        val cameraModes = cameraCapabilities.flatMap { it.modes }
        val rawCameraMode = cameraModes.firstOrNull {
            it.lens == CameraLens.Back &&
                it.width == TARGET_WIDTH &&
                it.height == TARGET_HEIGHT &&
                it.fps == TARGET_FPS
        }
        val back4kCandidate = cameraModes.firstOrNull {
            it.lens == CameraLens.Back &&
                it.width == TARGET_WIDTH &&
                it.height == TARGET_HEIGHT
        }

        val hardwareProbe = HardwareVideoCapabilityProbe()
        val hardwareSupports = hardwareProbe.supportsAvc(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = capabilityBitrateMbps * 1_000_000,
            bitrateMode = VideoBitrateMode.Cbr,
            profilePreference = AvcProfilePreference.Auto,
        )
        val resolver = StreamingCapabilityResolver(context)
        val capabilityModes = resolver.resolve(
            bitrate = capabilityBitrateMbps * 1_000_000,
            bitrateMode = VideoBitrateMode.Cbr,
            profilePreference = AvcProfilePreference.Auto,
        )
        val targetMode = capabilityModes.firstOrNull {
            it.lens == CameraLens.Back &&
                it.width == TARGET_WIDTH &&
                it.height == TARGET_HEIGHT &&
                it.fps == TARGET_FPS
        }

        val trialCamera2Ae60 = back4kCandidate?.let { mode ->
            camera2CanAttempt60(context, mode.cameraId)
        } == true
        val forcedCamera2Trial = rawCameraMode == null &&
            back4kCandidate != null &&
            trialCamera2Ae60 &&
            hardwareSupports

        if ((rawCameraMode == null && !forcedCamera2Trial) || !hardwareSupports) {
            assertTrue(
                "Resolver must not advertise 4K60 when the strict Camera2 probe or hardware AVC cannot satisfy the requested gate",
                targetMode == null,
            )
            writePreflightEvidence(
                filesDir = context.filesDir,
                modeSupported = false,
                cameraId = rawCameraMode?.cameraId ?: back4kCandidate?.cameraId,
                rawCameraSupports = false,
                strictCamera2ProbeSupports = rawCameraMode != null,
                forcedCamera2Trial = false,
                camera2TrialAe60 = trialCamera2Ae60,
                hardwareSupports = hardwareSupports,
                highProfileAvailable = false,
                maxHardwareBitrate = null,
                profilePreference = AvcProfilePreference.Auto,
                capabilityBitrateMbps = capabilityBitrateMbps,
                streamBitrateMbps = streamBitrateMbps,
                durationSeconds = durationSeconds,
                receiverHost = receiverHost,
                receiverPort = receiverPort,
                latencyMs = latencyMs,
            )
            return
        }

        if (forcedCamera2Trial) {
            assertTrue(
                "Forced Camera2 trial is only valid when the conservative resolver has not already advertised 4K60",
                targetMode == null,
            )
        } else {
            assertNotNull(
                "Camera2 + hardware AVC support 4K60 independently, so resolver must advertise the same mode",
                targetMode,
            )
        }

        val effectiveCameraId = rawCameraMode?.cameraId ?: back4kCandidate!!.cameraId
        val highProfileAvailable = hardwareProbe.supportsHighProfile(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = capabilityBitrateMbps * 1_000_000,
        )
        val profilePreference = if (highProfileAvailable) {
            AvcProfilePreference.High
        } else {
            AvcProfilePreference.Auto
        }

        if (forcedCamera2Trial) {
            assertTrue(
                "Hardware AVC must support the forced 4K60 trial at the E2E network bitrate",
                hardwareProbe.supportsAvc(
                    width = TARGET_WIDTH,
                    height = TARGET_HEIGHT,
                    fps = TARGET_FPS,
                    bitrate = streamBitrateMbps * 1_000_000,
                    bitrateMode = VideoBitrateMode.Cbr,
                    profilePreference = profilePreference,
                ),
            )
        } else {
            val networkModes = resolver.resolve(
                bitrate = streamBitrateMbps * 1_000_000,
                bitrateMode = VideoBitrateMode.Cbr,
                profilePreference = profilePreference,
            )
            assertTrue(
                "The same physical 4K60 path must support the E2E stream bitrate $streamBitrateMbps Mbps",
                networkModes.any {
                    it.cameraId == effectiveCameraId &&
                        it.lens == CameraLens.Back &&
                        it.width == TARGET_WIDTH &&
                        it.height == TARGET_HEIGHT &&
                        it.fps == TARGET_FPS
                },
            )
        }

        val config = StreamConfig.Baseline1080p30.copy(
            width = TARGET_WIDTH,
            height = TARGET_HEIGHT,
            fps = TARGET_FPS,
            bitrate = streamBitrateMbps * 1_000_000,
            keyframeIntervalSeconds = TARGET_KEYFRAME_SECONDS,
            latencyMs = latencyMs,
            codecPreference = CodecPreference.ForceAvc,
            videoBitrateMode = VideoBitrateMode.Cbr,
            avcProfilePreference = profilePreference,
            bFramesEnabled = false,
            audioEnabled = true,
            audioSampleRate = TARGET_AUDIO_SAMPLE_RATE,
            audioChannelCount = TARGET_AUDIO_CHANNELS,
            audioBitrate = TARGET_AUDIO_BITRATE,
        )
        StreamConfigStore.save(context, config)
        StreamConfig.installRuntimeConfig(config)

        writePreflightEvidence(
            filesDir = context.filesDir,
            modeSupported = true,
            cameraId = effectiveCameraId,
            // Compatibility field used by the Linux harness. In trial mode it
            // means Camera2 exposes 4K output + AE60 and is being validated by
            // a real session; strictCamera2ProbeSupports4k60 keeps the static
            // probe result explicit so this is not mistaken for metadata proof.
            rawCameraSupports = rawCameraMode != null || forcedCamera2Trial,
            strictCamera2ProbeSupports = rawCameraMode != null,
            forcedCamera2Trial = forcedCamera2Trial,
            camera2TrialAe60 = trialCamera2Ae60,
            hardwareSupports = true,
            highProfileAvailable = highProfileAvailable,
            maxHardwareBitrate = hardwareProbe.maxBitrateFor(TARGET_WIDTH, TARGET_HEIGHT, TARGET_FPS),
            profilePreference = profilePreference,
            capabilityBitrateMbps = capabilityBitrateMbps,
            streamBitrateMbps = streamBitrateMbps,
            durationSeconds = durationSeconds,
            receiverHost = receiverHost,
            receiverPort = receiverPort,
            latencyMs = latencyMs,
        )

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        ) as MainActivity
        instrumentation.waitForIdleSync()
        SystemClock.sleep(STARTUP_SETTLE_MS)

        val targetUri = Uri.Builder()
            .scheme("openstream")
            .authority("connect")
            .appendQueryParameter("host", receiverHost)
            .appendQueryParameter("port", receiverPort.toString())
            .appendQueryParameter("latency", latencyMs.toString())
            .appendQueryParameter("bitrateMbps", streamBitrateMbps.toString())
            .appendQueryParameter("name", "Phase 4 device E2E")
            .build()
        val pairingIntent = Intent(Intent.ACTION_VIEW, targetUri, context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        instrumentation.runOnMainSync { activity.startActivity(pairingIntent) }
        instrumentation.waitForIdleSync()

        assertTrue(
            "OpenStream did not reach LIVE state within ${CONNECT_TIMEOUT_MS}ms",
            waitForLiveState(instrumentation, activity, CONNECT_TIMEOUT_MS),
        )

        val deadline = SystemClock.elapsedRealtime() + durationSeconds * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue(
                "OpenStream left LIVE state during the 4K60 E2E sample",
                isLive(instrumentation, activity),
            )
            SystemClock.sleep(LIVE_POLL_MS)
        }

        val streamInfo = readStreamInfo(instrumentation, activity)
        val counts = FRAME_INFO.find(streamInfo)
        assertNotNull("Stream telemetry must expose frame/keyframe counters, got: $streamInfo", counts)
        val frames = counts!!.groupValues[1].toLong()
        val keyframes = counts.groupValues[2].toLong()
        assertTrue("4K60 stream must deliver encoded frames, got: $streamInfo", frames > 0)
        assertTrue("4K60 stream must deliver keyframes, got: $streamInfo", keyframes > 0)

        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun camera2CanAttempt60(context: Context, cameraId: String): Boolean {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val has4kCodecSurface = runCatching {
            map.getOutputSizes(MediaCodec::class.java)?.any {
                it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT
            } == true
        }.getOrDefault(false)
        val aeAllows60 = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.any { TARGET_FPS in it.lower..it.upper }
            ?: false
        return has4kCodecSurface && aeAllows60
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

    private fun readStreamInfo(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): String {
        var value = ""
        instrumentation.runOnMainSync {
            value = activity.findViewById<android.widget.TextView>(R.id.streamInfoChip).text.toString()
        }
        return value
    }

    private fun writePreflightEvidence(
        filesDir: File,
        modeSupported: Boolean,
        cameraId: String?,
        rawCameraSupports: Boolean,
        strictCamera2ProbeSupports: Boolean,
        forcedCamera2Trial: Boolean,
        camera2TrialAe60: Boolean,
        hardwareSupports: Boolean,
        highProfileAvailable: Boolean,
        maxHardwareBitrate: Int?,
        profilePreference: AvcProfilePreference,
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
            .put("modeSupported", modeSupported)
            .put("rawCameraSupports4k60", rawCameraSupports)
            .put("strictCamera2ProbeSupports4k60", strictCamera2ProbeSupports)
            .put("forcedCamera2Trial", forcedCamera2Trial)
            .put("camera2TrialAe60", camera2TrialAe60)
            .put(
                "camera2SupportBasis",
                when {
                    strictCamera2ProbeSupports -> "strict-static-probe"
                    forcedCamera2Trial -> "4k-output-plus-ae60-runtime-trial"
                    else -> "unsupported"
                },
            )
            .put("hardwareAvcSupports4k60", hardwareSupports)
            .put("cameraId", cameraId ?: JSONObject.NULL)
            .put("lens", CameraLens.Back.name)
            .put("width", TARGET_WIDTH)
            .put("height", TARGET_HEIGHT)
            .put("fps", TARGET_FPS)
            .put("capabilityBitrateMbps", capabilityBitrateMbps)
            .put("streamBitrateMbps", streamBitrateMbps)
            .put("videoBitrateMode", VideoBitrateMode.Cbr.name)
            .put("profilePreference", profilePreference.name)
            .put("highProfileAvailable", highProfileAvailable)
            .put("maxHardwareBitrate", maxHardwareBitrate ?: JSONObject.NULL)
            .put("bFramesRequested", false)
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
        private const val TARGET_FPS = 60
        private const val TARGET_KEYFRAME_SECONDS = 2
        private const val TARGET_AUDIO_SAMPLE_RATE = 48_000
        private const val TARGET_AUDIO_CHANNELS = 1
        private const val TARGET_AUDIO_BITRATE = 128_000

        private const val DEFAULT_RECEIVER_PORT = 19001
        private const val DEFAULT_DURATION_SECONDS = 15
        private const val MIN_DURATION_SECONDS = 8
        private const val MAX_DURATION_SECONDS = 120
        private const val DEFAULT_STREAM_BITRATE_MBPS = 8
        private const val DEFAULT_CAPABILITY_BITRATE_MBPS = 35
        private const val DEFAULT_LATENCY_MS = 2_000
        private const val STARTUP_SETTLE_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val LIVE_POLL_MS = 500L
        private val FRAME_INFO = Regex("(\\d+) f · (\\d+) kf")

        const val PREFLIGHT_EVIDENCE_FILE = "phase4-device-e2e-preflight.json"
    }
}
