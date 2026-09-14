#!/usr/bin/env python3
"""Automated Phase 3 device acceptance for OpenStream.

The Linux host runs an SRT listener while Android instrumentation configures a
real phone for 3840x2160@30, hardware AVC and microphone AAC, then streams to
this host. The harness records the MPEG-TS sample and validates it with
ffprobe. Network throughput is intentionally *not* a 20-40 Mbps performance
gate here; use --stream-bitrate-mbps=8 over a slow Tailscale path and keep the
30 Mbps hardware capability check via --capability-bitrate-mbps=30.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from typing import Any

APP_ID = "dev.openstream.app"
TEST_PACKAGE = "dev.openstream.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.openstream.app.Phase3DeviceE2eTest#stream4k30WithMicrophoneToSrtReceiver"
PREFLIGHT_FILE = "phase3-device-e2e-preflight.json"


@dataclass(frozen=True)
class Config:
    adb_serial: str
    receiver_host: str
    app_apk: Path
    test_apk: Path
    receiver_port: int
    duration_seconds: int
    stream_bitrate_mbps: int
    capability_bitrate_mbps: int
    latency_ms: int
    evidence_dir: Path


def require_binary(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Thiếu chương trình bắt buộc trên PATH: {name}")
    return path


def run(
    command: list[str],
    *,
    check: bool = True,
    timeout: float | None = None,
    capture_output: bool = True,
) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(command), flush=True)
    return subprocess.run(
        command,
        check=check,
        timeout=timeout,
        text=True,
        capture_output=capture_output,
    )


def adb(config: Config, *args: str, check: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return run(["adb", "-s", config.adb_serial, *args], check=check, timeout=timeout)


def parse_args(argv: list[str]) -> Config:
    parser = argparse.ArgumentParser(
        description="Nghiệm thu tự động Giai đoạn 3 trên Android thật + Linux SRT receiver.",
    )
    parser.add_argument(
        "--adb-serial",
        default=os.environ.get("OPENSTREAM_ADB_SERIAL"),
        help="ADB serial, ví dụ 100.68.1.23:5555. Có thể dùng OPENSTREAM_ADB_SERIAL.",
    )
    parser.add_argument(
        "--receiver-host",
        default=os.environ.get("OPENSTREAM_RECEIVER_HOST"),
        help="IP Linux mà Android truy cập được, thường là Tailscale IPv4. Có thể dùng OPENSTREAM_RECEIVER_HOST.",
    )
    parser.add_argument("--app-apk", type=Path, default=Path("dist/openstream-android.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("dist/openstream-android-test.apk"))
    parser.add_argument("--receiver-port", type=int, default=19000)
    parser.add_argument("--duration-seconds", type=int, default=15)
    parser.add_argument(
        "--stream-bitrate-mbps",
        type=int,
        default=8,
        help="Bitrate cho E2E mạng. 8 Mbps là mức thấp nhất của Phase 3 để giảm phụ thuộc tốc độ Tailscale.",
    )
    parser.add_argument(
        "--capability-bitrate-mbps",
        type=int,
        default=30,
        help="Bitrate dùng để gate Camera2 + hardware MediaCodec capability; mặc định 30 Mbps.",
    )
    parser.add_argument("--latency-ms", type=int, default=2000)
    parser.add_argument("--evidence-dir", type=Path, default=Path("build/phase3-device-e2e"))
    args = parser.parse_args(argv)

    if not args.adb_serial:
        parser.error("cần --adb-serial hoặc OPENSTREAM_ADB_SERIAL")
    if not args.receiver_host:
        parser.error("cần --receiver-host hoặc OPENSTREAM_RECEIVER_HOST")
    if not 1 <= args.receiver_port <= 65535:
        parser.error("--receiver-port phải nằm trong 1..65535")
    if not 8 <= args.duration_seconds <= 120:
        parser.error("--duration-seconds phải nằm trong 8..120")
    if not 8 <= args.stream_bitrate_mbps <= 50:
        parser.error("--stream-bitrate-mbps phải nằm trong 8..50 trên nhánh Phase 3")
    if not 8 <= args.capability_bitrate_mbps <= 50:
        parser.error("--capability-bitrate-mbps phải nằm trong 8..50 trên nhánh Phase 3")
    if not 20 <= args.latency_ms <= 10_000:
        parser.error("--latency-ms phải nằm trong 20..10000")

    return Config(
        adb_serial=args.adb_serial,
        receiver_host=args.receiver_host,
        app_apk=args.app_apk,
        test_apk=args.test_apk,
        receiver_port=args.receiver_port,
        duration_seconds=args.duration_seconds,
        stream_bitrate_mbps=args.stream_bitrate_mbps,
        capability_bitrate_mbps=args.capability_bitrate_mbps,
        latency_ms=args.latency_ms,
        evidence_dir=args.evidence_dir,
    )


def ensure_srt(ffmpeg: str) -> None:
    protocols = run([ffmpeg, "-hide_banner", "-protocols"]).stdout.split()
    if "srt" not in protocols:
        raise SystemExit("FFmpeg trên Linux không hỗ trợ protocol SRT")


def connect_device(config: Config) -> None:
    # For TCP ADB targets, adb connect is idempotent. USB serials skip this step.
    if ":" in config.adb_serial:
        result = run(["adb", "connect", config.adb_serial], check=False)
        print((result.stdout or result.stderr).strip())
    adb(config, "wait-for-device", timeout=30)
    state = adb(config, "get-state").stdout.strip()
    if state != "device":
        raise SystemExit(f"ADB target chưa sẵn sàng: {config.adb_serial} ({state})")


def install_test_build(config: Config) -> None:
    for path in (config.app_apk, config.test_apk):
        if not path.is_file():
            raise SystemExit(f"Không tìm thấy APK: {path}")
    adb(config, "install", "-r", "-t", str(config.app_apk), timeout=120)
    adb(config, "install", "-r", "-t", str(config.test_apk), timeout=120)
    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.CAMERA")
    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.RECORD_AUDIO")
    adb(config, "shell", "am", "force-stop", APP_ID)
    adb(config, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb(config, "shell", "wm", "dismiss-keyguard", check=False)
    adb(config, "logcat", "-c", check=False)


def start_receiver(config: Config, ffmpeg: str, capture_path: Path, log_path: Path) -> tuple[subprocess.Popen[str], Any]:
    url = f"srt://0.0.0.0:{config.receiver_port}?mode=listener&latency={config.latency_ms}"
    log_file = log_path.open("w", encoding="utf-8")
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "info",
        "-y",
        "-i",
        url,
        "-map",
        "0",
        "-c",
        "copy",
        "-f",
        "mpegts",
        str(capture_path),
    ]
    print("+", " ".join(command), flush=True)
    process = subprocess.Popen(
        command,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    time.sleep(1.0)
    if process.poll() is not None:
        log_file.close()
        raise SystemExit(f"FFmpeg receiver thoát sớm; xem {log_path}")
    return process, log_file


def run_instrumentation(config: Config, output_path: Path) -> subprocess.CompletedProcess[str]:
    command = [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "receiverHost",
        config.receiver_host,
        "-e",
        "receiverPort",
        str(config.receiver_port),
        "-e",
        "durationSeconds",
        str(config.duration_seconds),
        "-e",
        "streamBitrateMbps",
        str(config.stream_bitrate_mbps),
        "-e",
        "capabilityBitrateMbps",
        str(config.capability_bitrate_mbps),
        "-e",
        "latencyMs",
        str(config.latency_ms),
        "-e",
        "class",
        TEST_CLASS,
        f"{TEST_PACKAGE}/{RUNNER}",
    ]
    result = adb(
        config,
        *command,
        check=False,
        timeout=config.duration_seconds + 90,
    )
    output_path.write_text((result.stdout or "") + (result.stderr or ""), encoding="utf-8")
    return result


def stop_receiver(process: subprocess.Popen[str], log_file: Any) -> None:
    try:
        process.wait(timeout=12)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    finally:
        log_file.close()


def collect_device_evidence(config: Config, directory: Path) -> dict[str, Any]:
    logcat = adb(config, "logcat", "-d", "-v", "threadtime", check=False).stdout
    (directory / "device-logcat.txt").write_text(logcat, encoding="utf-8")

    preflight_result = adb(
        config,
        "shell",
        "run-as",
        APP_ID,
        "cat",
        f"files/{PREFLIGHT_FILE}",
        check=False,
    )
    preflight_text = preflight_result.stdout.strip()
    if not preflight_text:
        return {}
    (directory / PREFLIGHT_FILE).write_text(preflight_text + "\n", encoding="utf-8")
    try:
        return json.loads(preflight_text)
    except json.JSONDecodeError:
        return {}


def ffprobe_json(ffprobe: str, capture_path: Path, output_path: Path) -> dict[str, Any]:
    result = run(
        [
            ffprobe,
            "-v",
            "error",
            "-count_frames",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(capture_path),
        ],
        check=False,
    )
    output_path.write_text(result.stdout or "{}", encoding="utf-8")
    if result.returncode != 0:
        return {}
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {}


def ffprobe_frames(ffprobe: str, capture_path: Path, output_path: Path) -> dict[str, Any]:
    result = run(
        [
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_frames",
            "-show_entries",
            "frame=key_frame,best_effort_timestamp_time,pict_type",
            "-of",
            "json",
            str(capture_path),
        ],
        check=False,
    )
    output_path.write_text(result.stdout or "{}", encoding="utf-8")
    if result.returncode != 0:
        return {}
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {}


def rate_to_float(value: str | None) -> float | None:
    if not value or value in {"0/0", "N/A"}:
        return None
    try:
        return float(Fraction(value))
    except (ValueError, ZeroDivisionError):
        return None


def duration_seconds(probe: dict[str, Any]) -> float | None:
    raw = probe.get("format", {}).get("duration")
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def keyframe_intervals(frame_probe: dict[str, Any]) -> list[float]:
    timestamps: list[float] = []
    for frame in frame_probe.get("frames", []):
        if int(frame.get("key_frame", 0)) != 1:
            continue
        try:
            timestamps.append(float(frame["best_effort_timestamp_time"]))
        except (KeyError, TypeError, ValueError):
            continue
    return [b - a for a, b in zip(timestamps, timestamps[1:]) if b > a]


def validate(
    config: Config,
    instrumentation: subprocess.CompletedProcess[str],
    preflight: dict[str, Any],
    probe: dict[str, Any],
    frame_probe: dict[str, Any],
    logcat: str,
    capture_path: Path,
) -> dict[str, Any]:
    streams = probe.get("streams", [])
    videos = [stream for stream in streams if stream.get("codec_type") == "video"]
    audios = [stream for stream in streams if stream.get("codec_type") == "audio"]
    video = videos[0] if videos else {}
    audio = audios[0] if audios else {}

    fps = rate_to_float(video.get("avg_frame_rate")) or rate_to_float(video.get("r_frame_rate"))
    duration = duration_seconds(probe)
    intervals = keyframe_intervals(frame_probe)
    median_keyframe_interval = None
    if intervals:
        ordered = sorted(intervals)
        median_keyframe_interval = ordered[len(ordered) // 2]

    high_available = bool(preflight.get("highProfileAvailable", False))
    actual_profile = str(video.get("profile") or "")
    hardware_match = re.search(r"Using hardware encoder\s+([^\s]+)", logcat)
    hardware_codec_name = hardware_match.group(1) if hardware_match else None
    capture_bitrate_mbps = None
    if duration and duration > 0 and capture_path.is_file():
        capture_bitrate_mbps = capture_path.stat().st_size * 8 / duration / 1_000_000

    checks: dict[str, bool] = {
        "instrumentation_passed": instrumentation.returncode == 0 and "FAILURES!!!" not in (instrumentation.stdout or ""),
        "preflight_evidence_present": bool(preflight),
        "camera2_hardware_capability_4k30_at_30mbps": (
            int(preflight.get("width", 0)) == 3840
            and int(preflight.get("height", 0)) == 2160
            and int(preflight.get("fps", 0)) == 30
            and int(preflight.get("capabilityBitrateMbps", 0)) == config.capability_bitrate_mbps
        ),
        "video_h264": video.get("codec_name") == "h264",
        "video_3840x2160": int(video.get("width", 0)) == 3840 and int(video.get("height", 0)) == 2160,
        "video_near_30fps": fps is not None and 28.0 <= fps <= 32.0,
        "avc_high_when_supported": (not high_available) or ("high" in actual_profile.lower()),
        "hardware_video_encoder_logged": hardware_codec_name is not None,
        "no_video_encoder_failure": "MediaCodec encoder error" not in logcat and "No hardware surface encoder" not in logcat,
        "audio_aac": audio.get("codec_name") == "aac",
        "audio_48khz": str(audio.get("sample_rate") or "") == "48000",
        "audio_channel_present": int(audio.get("channels") or 0) >= 1,
        "microphone_capture_started": "Using audio source" in logcat,
        "minimum_sample_duration": duration is not None and duration >= min(8.0, config.duration_seconds * 0.5),
        "keyframe_near_2_seconds": (
            median_keyframe_interval is not None and 1.4 <= median_keyframe_interval <= 2.6
        ),
    }

    return {
        "passed": all(checks.values()),
        "checks": checks,
        "observed": {
            "device": {
                "manufacturer": preflight.get("manufacturer"),
                "model": preflight.get("model"),
                "sdk": preflight.get("sdk"),
                "cameraId": preflight.get("cameraId"),
            },
            "video": {
                "codec": video.get("codec_name"),
                "profile": video.get("profile"),
                "width": video.get("width"),
                "height": video.get("height"),
                "avgFrameRate": fps,
                "hardwareCodecNameFromLog": hardware_codec_name,
            },
            "audio": {
                "codec": audio.get("codec_name"),
                "sampleRate": audio.get("sample_rate"),
                "channels": audio.get("channels"),
            },
            "sample": {
                "durationSeconds": duration,
                "captureAverageMbps": capture_bitrate_mbps,
                "medianKeyframeIntervalSeconds": median_keyframe_interval,
                "keyframeIntervalsSeconds": intervals,
            },
            "requested": {
                "streamBitrateMbps": config.stream_bitrate_mbps,
                "capabilityBitrateMbps": config.capability_bitrate_mbps,
                "latencyMs": config.latency_ms,
            },
        },
        "performanceGate": {
            "included": False,
            "reason": "Tailscale E2E validates function only; 20-40 Mbps sustained LAN performance remains a separate manual acceptance item.",
        },
    }


def main(argv: list[str]) -> int:
    config = parse_args(argv)
    require_binary("adb")
    ffmpeg = require_binary("ffmpeg")
    ffprobe = require_binary("ffprobe")
    ensure_srt(ffmpeg)

    config.evidence_dir.mkdir(parents=True, exist_ok=True)
    capture_path = config.evidence_dir / "phase3-e2e.ts"
    receiver_log = config.evidence_dir / "receiver-ffmpeg.log"
    instrumentation_log = config.evidence_dir / "instrumentation.txt"

    connect_device(config)
    install_test_build(config)

    receiver, receiver_log_file = start_receiver(config, ffmpeg, capture_path, receiver_log)
    try:
        instrumentation = run_instrumentation(config, instrumentation_log)
    finally:
        stop_receiver(receiver, receiver_log_file)

    preflight = collect_device_evidence(config, config.evidence_dir)
    logcat = (config.evidence_dir / "device-logcat.txt").read_text(encoding="utf-8")

    probe = {}
    frame_probe = {}
    if capture_path.is_file() and capture_path.stat().st_size > 0:
        probe = ffprobe_json(ffprobe, capture_path, config.evidence_dir / "ffprobe.json")
        frame_probe = ffprobe_frames(ffprobe, capture_path, config.evidence_dir / "ffprobe-frames.json")
    else:
        (config.evidence_dir / "ffprobe.json").write_text("{}\n", encoding="utf-8")
        (config.evidence_dir / "ffprobe-frames.json").write_text("{}\n", encoding="utf-8")

    acceptance = validate(config, instrumentation, preflight, probe, frame_probe, logcat, capture_path)
    acceptance_path = config.evidence_dir / "acceptance.json"
    acceptance_path.write_text(json.dumps(acceptance, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(json.dumps(acceptance, indent=2, ensure_ascii=False))
    print(f"Evidence: {config.evidence_dir}")
    if not acceptance["passed"]:
        failed = [name for name, passed in acceptance["checks"].items() if not passed]
        print("FAILED checks:", ", ".join(failed), file=sys.stderr)
        return 1
    print("Phase 3 functional device E2E: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
