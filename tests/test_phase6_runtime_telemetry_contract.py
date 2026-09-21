from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRT = ROOT / "android/app/src/main/java/com/synclab/airlens/stream/SrtStreamClient.kt"
TELEMETRY = ROOT / "android/app/src/main/java/com/synclab/airlens/telemetry/TelemetrySampler.kt"
RATE = ROOT / "android/app/src/main/java/com/synclab/airlens/telemetry/SendRateMeter.kt"


def test_transport_stats_keep_video_audio_and_lifetime_counters_separate() -> None:
    source = SRT.read_text(encoding="utf-8")

    assert "val audioAccessUnitsSent: Long = 0" in source
    assert "val audioBytesSent: Long = 0" in source
    assert "val lifetimeBytesSent: Long = 0" in source
    assert "val lifetimeAccessUnitsSent: Long = 0" in source
    assert "audioAccessUnitsSent.incrementAndGet()" in source
    assert source.count("lifetimeBytesSent.addAndGet(payloadBytes)") == 2
    assert "private fun resetSessionStats()" in source
    reset = source[source.index("private fun resetSessionStats()") : source.index("private fun markSendFailure")]
    assert "lifetimeBytesSent.set(0)" not in reset
    assert "connectionLosses.set(0)" not in reset


def test_connection_loss_is_counted_once_per_transport_generation() -> None:
    source = SRT.read_text(encoding="utf-8")

    assert "val reconnects: Long = 0" in source
    assert "successfulConnections.incrementAndGet()" in source
    assert "(connections - 1L).coerceAtLeast(0L)" in source
    failure_start = source.index("private fun markSendFailure")
    failure_end = source.index("companion object", failure_start)
    failure = source[failure_start:failure_end]
    assert "failedGeneration.get() != generation" in failure
    assert "connectionLosses.incrementAndGet()" in failure
    assert "connected = false" in failure


def test_rate_meter_is_constant_memory_and_uses_monotonic_time() -> None:
    source = RATE.read_text(encoding="utf-8")

    assert "private var lastBytes: Long?" in source
    assert "private var lastTimestampNanos: Long?" in source
    assert "System.nanoTime()" in source
    assert "deltaBytes * 8.0 * 1_000_000_000.0 / deltaNanos" in source
    assert "List<" not in source
    assert "MutableList" not in source


def test_device_telemetry_records_wifi_battery_temperature_and_thermal_state() -> None:
    source = TELEMETRY.read_text(encoding="utf-8")

    assert "BatteryManager.EXTRA_TEMPERATURE" in source
    assert "currentThermalStatus" in source
    assert "wifiRssi" in source
    assert "batteryPercent" in source
    assert "thermalStatus" in source
