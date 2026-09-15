import sys
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "phase5_obs_e2e.py"
SPEC = spec_from_file_location("phase5_obs_e2e", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def healthy_log() -> str:
    return "\n".join(
        [
            "[OpenStream] OBS plugin loaded: V8 — video + audio + remote controls",
            "[OpenStream] Receiving 3840x2160 video stream codec=h264 + audio",
            "[OpenStream] Opened audio decoder: aac, 48000 Hz, 1 channels",
            "[OpenStream] Output 1 decoded YUV frame(s) to OBS (3840x2160, source format=yuv420p)",
            "[OpenStream] Holding CAM A for reconnect",
            "[OpenStream] Receiving 3840x2160 video stream codec=h264 + audio",
            "[OpenStream] Opened audio decoder: aac, 48000 Hz, 1 channels",
        ]
    )


def test_phase5_parser_accepts_two_realistic_4k30_audio_sessions_with_reconnect() -> None:
    result = MODULE.parse_obs_log(healthy_log())

    assert result["passed"] is True
    assert result["gates"] == {
        "pluginLoaded": True,
        "two4k30H264AudioSessions": True,
        "aac48kAudioDecoded": True,
        "decoded4kFrameOutputToObs": True,
        "reconnectObservedBetweenSessions": True,
    }
    assert len(result["receives"]) == 2


def test_phase5_parser_rejects_video_only_stream() -> None:
    log = healthy_log().replace(" codec=h264 + audio", " codec=h264")
    result = MODULE.parse_obs_log(log)

    assert result["passed"] is False
    assert result["gates"]["two4k30H264AudioSessions"] is False


def test_phase5_parser_rejects_wrong_resolution_even_if_obs_outputs_frames() -> None:
    log = healthy_log().replace("3840x2160", "1920x1080")
    result = MODULE.parse_obs_log(log)

    assert result["passed"] is False
    assert result["gates"]["two4k30H264AudioSessions"] is False
    assert result["gates"]["decoded4kFrameOutputToObs"] is False


def test_phase5_parser_requires_reconnect_between_first_and_second_session() -> None:
    log = healthy_log().replace("[OpenStream] Holding CAM A for reconnect\n", "")
    result = MODULE.parse_obs_log(log)

    assert result["passed"] is False
    assert result["gates"]["reconnectObservedBetweenSessions"] is False


def test_phase5_parser_rejects_wrong_audio_contract() -> None:
    log = healthy_log().replace("aac, 48000 Hz, 1 channels", "aac, 44100 Hz, 2 channels")
    result = MODULE.parse_obs_log(log)

    assert result["passed"] is False
    assert result["gates"]["aac48kAudioDecoded"] is False
