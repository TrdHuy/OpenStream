from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main/java/dev/openstream/app/MainActivity.kt"


def test_caller_transport_failure_preserves_target_and_schedules_reconnect() -> None:
    source = MAIN.read_text(encoding="utf-8")

    assert "private var activeCallerTarget: ConnectionTarget? = null" in source
    assert "private var callerReconnectRunnable: Runnable? = null" in source
    assert "preserveCallerMode = true" in source
    assert "preserveCallerTarget = true" in source
    assert "scheduleCallerReconnect(target, \"media/transport failure\")" in source
    assert "startPhoneServerIfAllowed()" not in source[
        source.index("private fun handleMediaTransportFailure") :
        source.index("private fun useStreamBitrate")
    ]


def test_failed_caller_connect_retries_instead_of_silently_switching_to_listener() -> None:
    source = MAIN.read_text(encoding="utf-8")
    start = source.index("private fun startStream(target: ConnectionTarget, reconnecting: Boolean = false)")
    end = source.index("private fun scheduleCallerReconnect", start)
    start_stream = source[start:end]

    assert "callerModeActive = false" not in start_stream
    assert "startPhoneServerIfAllowed()" not in start_stream
    assert "scheduleCallerReconnect(target, error.message ?: \"connect failed\")" in start_stream
    assert "activeCallerTarget != target" in start_stream


def test_reconnect_has_bounded_backoff_and_user_stop_cancels_it() -> None:
    source = MAIN.read_text(encoding="utf-8")

    assert "CALLER_RECONNECT_BASE_DELAY_MS = 750L" in source
    assert "CALLER_RECONNECT_MAX_DELAY_MS = 5_000L" in source
    assert "callerReconnectRunnable?.let(mainHandler::removeCallbacks)" in source

    stop_start = source.index("private fun stopStream(")
    stop_end = source.index("private fun stopActiveEncoding", stop_start)
    stop_stream = source[stop_start:stop_end]
    assert "activeCallerTarget = null" in stop_stream
    assert "cancelCallerReconnect()" in stop_stream


def test_phase3_counter_chip_is_preserved_while_detail_gets_phase6_telemetry() -> None:
    source = MAIN.read_text(encoding="utf-8")

    assert '"%d f · %d kf · %.1f Mb"' in source
    assert "sendRateMeter.sample(stats.lifetimeBytesSent)" in source
    assert "stats.audioAccessUnitsSent" in source
    assert "stats.reconnects" in source
    assert "stats.connectionLosses" in source
    assert 'Log.i(\n                "OpenStreamTelemetry"' in source
    assert "device.wifiRssi" in source
    assert "device.temperatureCelsius" in source
