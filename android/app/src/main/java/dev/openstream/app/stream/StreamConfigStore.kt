package dev.openstream.app.stream

import android.content.Context

/**
 * Nguồn duy nhất để lưu/khôi phục cấu hình phát. Giai đoạn 1 chủ động dùng
 * SharedPreferences để giữ tương thích với SettingsActivity hiện tại; lớp phát
 * hiện chỉ nhận StreamConfig và không phụ thuộc trực tiếp vào giao diện.
 */
object StreamConfigStore {
    const val PREFS_NAME = "openstream_settings"

    const val KEY_WIDTH = "stream_width"
    const val KEY_HEIGHT = "stream_height"
    const val KEY_FPS = "stream_fps"
    const val KEY_BITRATE_MBPS = "stream_bitrate_mbps"
    const val KEY_KEYFRAME_INTERVAL = "stream_keyframe_interval_seconds"
    const val KEY_LATENCY = "latency_ms"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
    const val KEY_AUDIO_CHANNEL_COUNT = "audio_channel_count"
    const val KEY_AUDIO_BITRATE_KBPS = "audio_bitrate_kbps"

    const val MIN_WIDTH = 320
    const val MAX_WIDTH = 8192
    const val MIN_HEIGHT = 240
    const val MAX_HEIGHT = 8192
    const val MIN_FPS = 1
    const val MAX_FPS = 240
    const val MIN_KEYFRAME_INTERVAL = 1
    const val MAX_KEYFRAME_INTERVAL = 10
    const val MIN_LATENCY_MS = 20
    const val MAX_LATENCY_MS = 10_000
    const val MIN_AUDIO_SAMPLE_RATE = 8_000
    const val MAX_AUDIO_SAMPLE_RATE = 192_000
    const val MIN_AUDIO_CHANNELS = 1
    const val MAX_AUDIO_CHANNELS = 2
    const val MIN_AUDIO_BITRATE_KBPS = 32
    const val MAX_AUDIO_BITRATE_KBPS = 512

    fun load(context: Context): StreamConfig {
        val defaults = StreamConfig.Baseline1080p30
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return defaults.copy(
            width = prefs.getInt(KEY_WIDTH, defaults.width).coerceIn(MIN_WIDTH, MAX_WIDTH),
            height = prefs.getInt(KEY_HEIGHT, defaults.height).coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            fps = prefs.getInt(KEY_FPS, defaults.fps).coerceIn(MIN_FPS, MAX_FPS),
            bitrate = prefs.getInt(KEY_BITRATE_MBPS, defaults.bitrateMbps)
                .coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS) * 1_000_000,
            keyframeIntervalSeconds = prefs.getInt(
                KEY_KEYFRAME_INTERVAL,
                defaults.keyframeIntervalSeconds,
            ).coerceIn(MIN_KEYFRAME_INTERVAL, MAX_KEYFRAME_INTERVAL),
            latencyMs = prefs.getInt(KEY_LATENCY, defaults.latencyMs)
                .coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS),
            audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, defaults.audioEnabled),
            audioSampleRate = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, defaults.audioSampleRate)
                .coerceIn(MIN_AUDIO_SAMPLE_RATE, MAX_AUDIO_SAMPLE_RATE),
            audioChannelCount = prefs.getInt(KEY_AUDIO_CHANNEL_COUNT, defaults.audioChannelCount)
                .coerceIn(MIN_AUDIO_CHANNELS, MAX_AUDIO_CHANNELS),
            audioBitrate = prefs.getInt(KEY_AUDIO_BITRATE_KBPS, defaults.audioBitrateKbps)
                .coerceIn(MIN_AUDIO_BITRATE_KBPS, MAX_AUDIO_BITRATE_KBPS) * 1_000,
        )
    }

    fun save(context: Context, config: StreamConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WIDTH, config.width)
            .putInt(KEY_HEIGHT, config.height)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE_MBPS, config.bitrateMbps)
            .putInt(KEY_KEYFRAME_INTERVAL, config.keyframeIntervalSeconds)
            .putInt(KEY_LATENCY, config.latencyMs)
            .putBoolean(KEY_AUDIO_ENABLED, config.audioEnabled)
            .putInt(KEY_AUDIO_SAMPLE_RATE, config.audioSampleRate)
            .putInt(KEY_AUDIO_CHANNEL_COUNT, config.audioChannelCount)
            .putInt(KEY_AUDIO_BITRATE_KBPS, config.audioBitrateKbps)
            .apply()
    }
}
