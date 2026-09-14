from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
sys.path.insert(0, str(TOOLS))
MODULE_PATH = TOOLS / "phase6_soak_e2e.py"
SPEC = spec_from_file_location("phase6_soak_e2e", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def test_parse_args_accepts_30_minute_30mbps_soak() -> None:
    config = MODULE.parse_args(
        [
            "--adb-serial",
            "100.1.2.3:5555",
            "--receiver-host",
            "100.4.5.6",
            "--duration-seconds",
            "1800",
            "--stream-bitrate-mbps",
            "30",
        ]
    )

    assert config.duration_seconds == 1800
    assert config.stream_bitrate_mbps == 30
    assert config.sample_period_seconds == 60


def test_extract_total_pss_samples_understands_modern_dumpsys_format() -> None:
    text = """
    Applications Memory Usage (in Kilobytes):
    TOTAL PSS: 123456            TOTAL RSS: 234567
    Applications Memory Usage (in Kilobytes):
    TOTAL PSS: 130000            TOTAL RSS: 240000
    """
    assert MODULE.extract_total_pss_samples(text) == [123456, 130000]


def test_validate_keeps_performance_gate_separate_from_functional_pass(tmp_path: Path) -> None:
    capture = tmp_path / "sample.ts"
    capture.write_bytes(b"x" * 2_000_000)
    config = MODULE.Config(
        adb_serial="device",
        receiver_host="100.4.5.6",
        app_apk=tmp_path / "app.apk",
        test_apk=tmp_path / "test.apk",
        receiver_port=19060,
        duration_seconds=60,
        stream_bitrate_mbps=30,
        capability_bitrate_mbps=30,
        latency_ms=2000,
        sample_period_seconds=60,
        evidence_dir=tmp_path,
    )
    instrumentation = subprocess.CompletedProcess(args=[], returncode=0, stdout="OK", stderr="")
    preflight = {
        "width": 3840,
        "height": 2160,
        "fps": 30,
        "streamBitrateMbps": 30,
        "highProfileAvailable": True,
    }
    probe = {
        "streams": [
            {
                "codec_type": "video",
                "codec_name": "h264",
                "profile": "High",
                "width": 3840,
                "height": 2160,
                "avg_frame_rate": "30/1",
            },
            {
                "codec_type": "audio",
                "codec_name": "aac",
                "sample_rate": "48000",
                "channels": 1,
            },
        ],
        "format": {"duration": "60.0"},
    }
    frame_probe = {
        "frames": [
            {"key_frame": 1, "best_effort_timestamp_time": "0.0"},
            {"key_frame": 1, "best_effort_timestamp_time": "2.0"},
            {"key_frame": 1, "best_effort_timestamp_time": "4.0"},
        ]
    }
    logcat = "Using hardware encoder c2.vendor.avc.encoder\nUsing audio source MIC"

    result = MODULE.validate(
        config,
        instrumentation,
        preflight,
        probe,
        frame_probe,
        logcat,
        capture,
        "TOTAL PSS: 100000\nTOTAL PSS: 101000\n",
    )

    assert result["passed"] is True
    assert result["performanceGate"]["included"] is False
    assert result["observed"]["soak"]["pssGrowthKiB"] == 1000
