package dev.openstream.app.stream

import dev.openstream.app.encoder.AvcProfilePreference
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.VideoBitrateMode

data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeIntervalSeconds: Int,
    val latencyMs: Int,
    val codecPreference: CodecPreference,
    val videoBitrateMode: VideoBitrateMode,
    val avcProfilePreference: AvcProfilePreference,
    val bFramesEnabled: Boolean,
    val audioEnabled: Boolean,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
) {
    val bitrateMbps: Int
        get() = bitrate / 1_000_000

    val audioBitrateKbps: Int
        get() = audioBitrate / 1_000

    companion object {
        // Giữ contract V1.0.1 cho đường điều khiển OBS cũ.
        const val MIN_BITRATE_MBPS = 8
        const val MAX_BITRATE_MBPS = 50

        // Giao diện phát nâng cao không bị khóa ở trần 50 Mbps; giá trị thực tế
        // vẫn phải được StreamingCapabilityResolver xác nhận với MediaCodec.
        const val MIN_CONFIGURABLE_BITRATE_MBPS = 1
        const val MAX_CONFIGURABLE_BITRATE_MBPS = 200

        private val baseline1080p30 = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 12_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            videoBitrateMode = VideoBitrateMode.Cbr,
            avcProfilePreference = AvcProfilePreference.Auto,
            bFramesEnabled = false,
            audioEnabled = true,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )

        @Volatile
        private var runtimeConfig: StreamConfig? = null

        val Baseline1080p30: StreamConfig
            get() = baseline1080p30

        val Default1080p30: StreamConfig
            get() = runtimeConfig ?: baseline1080p30

        fun installRuntimeConfig(config: StreamConfig) {
            runtimeConfig = config
        }

        val Fallback720p30 = StreamConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 8_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            videoBitrateMode = VideoBitrateMode.Cbr,
            avcProfilePreference = AvcProfilePreference.Auto,
            bFramesEnabled = false,
            audioEnabled = true,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )
    }
}
