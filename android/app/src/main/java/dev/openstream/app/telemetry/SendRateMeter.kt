package dev.openstream.app.telemetry

/**
 * Tính bitrate gửi thực tế từ bộ đếm byte tích lũy.
 *
 * Không tự đọc mạng hoặc giữ queue media. Caller đưa vào tổng số byte đã gửi và
 * thời điểm monotonic; class chỉ giữ đúng một mẫu trước đó nên memory là O(1).
 */
class SendRateMeter {
    private var lastBytes: Long? = null
    private var lastTimestampNanos: Long? = null

    fun sample(totalBytes: Long, timestampNanos: Long = System.nanoTime()): Double? {
        require(totalBytes >= 0) { "totalBytes must be non-negative" }
        val previousBytes = lastBytes
        val previousTimestamp = lastTimestampNanos
        lastBytes = totalBytes
        lastTimestampNanos = timestampNanos

        if (previousBytes == null || previousTimestamp == null) return null
        val deltaBytes = totalBytes - previousBytes
        val deltaNanos = timestampNanos - previousTimestamp
        if (deltaBytes < 0 || deltaNanos <= 0) return null
        return deltaBytes * 8.0 * 1_000_000_000.0 / deltaNanos
    }

    fun reset() {
        lastBytes = null
        lastTimestampNanos = null
    }
}
