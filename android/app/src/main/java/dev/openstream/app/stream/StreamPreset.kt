package dev.openstream.app.stream

/** User-facing presets. Availability is always checked against real Camera2 + MediaCodec capability. */
enum class StreamPreset(
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateMbps: Int,
    val keyframeIntervalSeconds: Int,
) {
    FullHd30("1080p30", 1920, 1080, 30, 12, 2),
    FullHd60("1080p60", 1920, 1080, 60, 20, 2),
    UltraHd30("4K30", 3840, 2160, 30, 30, 2),
    UltraHd60("4K60", 3840, 2160, 60, 35, 2),
    ;

    fun applyTo(base: StreamConfig): StreamConfig = base.copy(
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrateMbps * 1_000_000,
        keyframeIntervalSeconds = keyframeIntervalSeconds,
    )

    fun matches(config: StreamConfig): Boolean =
        config.width == width && config.height == height && config.fps == fps
}
