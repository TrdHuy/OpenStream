package dev.openstream.app.encoder

/** Cách yêu cầu MediaCodec điều khiển tốc độ bit. */
enum class VideoBitrateMode(val displayName: String) {
    SystemDefault("Mặc định hệ thống"),
    Cbr("CBR"),
    Vbr("VBR"),
}

/** Hồ sơ H.264 người dùng muốn dùng. Auto cho phép codec chọn, ưu tiên High cho 4K. */
enum class AvcProfilePreference(val displayName: String) {
    Auto("Tự động"),
    Baseline("Baseline"),
    Main("Main"),
    High("High"),
}
