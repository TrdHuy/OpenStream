package com.synclab.airlens.telemetry

/**
 * Tính tốc độ (số lượng / giây) từ một bộ đếm tích lũy — ví dụ khung hình đã gửi → fps thực tế.
 *
 * Bản sao của [SendRateMeter] nhưng trả về count/s thay vì bit/s. Không tự đọc mạng hoặc
 * giữ queue media. Caller đưa vào tổng số đếm và thời điểm monotonic; class chỉ giữ đúng
 * một mẫu trước đó nên memory là O(1) và mỗi lần gọi là O(1).
 */
class CountRateMeter {
    private var lastCount: Long? = null
    private var lastTimestampNanos: Long? = null

    /**
     * @return counts per second since the previous sample, or null for the first sample,
     *   a counter that went backwards (encoder restart), or a non-advancing clock.
     */
    fun sample(totalCount: Long, timestampNanos: Long = System.nanoTime()): Double? {
        require(totalCount >= 0) { "totalCount must be non-negative" }
        val previousCount = lastCount
        val previousTimestamp = lastTimestampNanos
        lastCount = totalCount
        lastTimestampNanos = timestampNanos

        if (previousCount == null || previousTimestamp == null) return null
        val deltaCount = totalCount - previousCount
        val deltaNanos = timestampNanos - previousTimestamp
        if (deltaCount < 0 || deltaNanos <= 0) return null
        return deltaCount * 1_000_000_000.0 / deltaNanos
    }

    fun reset() {
        lastCount = null
        lastTimestampNanos = null
    }
}
