#!/usr/bin/env python3
"""Phase 6 long-running 4K30 + microphone soak harness.

This harness intentionally keeps throughput acceptance separate from functional
correctness. It records the observed capture bitrate and device health samples,
but only a run on a suitable LAN/Wi-Fi environment may be used as the manual
20-40 Mbps performance evidence required by issue #8/#17.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import phase3_device_e2e as p3

APP_ID = "dev.openstream.app"
TEST_PACKAGE = "dev.openstream.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.openstream.app.Phase6SoakE2eTest#soak4k30WithMicrophone"
PREFLIGHT_FILE = "phase6-soak-preflight.json"


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
    sample_period_seconds: int
    evidence_dir: Path


def parse_args(argv: list[str]) -> Config:
    parser = argparse.ArgumentParser(description="Phase 6: soak 4K30 + mic on a real Android device.")
    parser.add_argument("--adb-serial", default=os.environ.get("OPENSTREAM_ADB_SERIAL"), required=False)
    parser.add_argument("--receiver-host", default=os.environ.get("OPENSTREAM_RECEIVER_HOST"), required=False)
    parser.add_argument("--app-apk", type=Path, default=Path("dist/openstream-android.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("dist/openstream-android-test.apk"))
    parser.add_argument("--receiver-port", type=int, default=19060)
    parser.add_argument("--duration-seconds", type=int, default=1800)
    parser.add_argument("--stream-bitrate-mbps", type=int, default=30)
    parser.add_argument("--capability-bitrate-mbps", type=int, default=30)
    parser.add_argument("--latency-ms", type=int, default=2000)
    parser.add_argument("--sample-period-seconds", type=int, default=60)
    parser.add_argument("--evidence-dir", type=Path, default=Path("build/phase6-soak-e2e"))
    args = parser.parse_args(argv)

    if not args.adb_serial:
        parser.error("cần --adb-serial hoặc OPENSTREAM_ADB_SERIAL")
    if not args.receiver_host:
        parser.error("cần --receiver-host hoặc OPENSTREAM_RECEIVER_HOST")
    if not 1 <= args.receiver_port <= 65535:
        parser.error("--receiver-port phải nằm trong 1..65535")
    if not 60 <= args.duration_seconds <= 3600:
        parser.error("--duration-seconds phải nằm trong 60..3600")
    if not 20 <= args.stream_bitrate_mbps <= 40:
        parser.error("--stream-bitrate-mbps phải nằm trong 20..40 cho soak Phase 6")
    if not 8 <= args.capability_bitrate_mbps <= 50:
        parser.error("--capability-bitrate-mbps phải nằm trong 8..50")
    if not 20 <= args.latency_ms <= 10_000:
        parser.error("--latency-ms phải nằm trong 20..10000")
    if not 10 <= args.sample_period_seconds <= 300:
        parser.error("--sample-period-seconds phải nằm trong 10..300")

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
        sample_period_seconds=args.sample_period_seconds,
        evidence_dir=args.evidence_dir,
    )


def adb(config: Config, *args: str, check: bool = True, timeout: float | None = None):
    return p3.run(["adb", "-s", config.adb_serial, *args], check=check, timeout=timeout)


def install_with_signature_recovery(config: Config) -> None:
    for path in (config.app_apk, config.test_apk):
        if not path.is_file():
            raise SystemExit(f"Không tìm thấy APK: {path}")

    first = adb(config, "install", "-r", "-t", str(config.app_apk), check=False, timeout=120)
    first_text = (first.stdout or "") + (first.stderr or "")
    if first.returncode != 0:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in first_text:
            raise SystemExit(f"Không cài được app APK:\n{first_text}")
        print("APK đang cài có chữ ký khác; gỡ test build cũ rồi cài sạch.", flush=True)
        adb(config, "uninstall", TEST_PACKAGE, check=False, timeout=60)
        adb(config, "uninstall", APP_ID, check=False, timeout=60)
        adb(config, "install", "-t", str(config.app_apk), timeout=120)

    test = adb(config, "install", "-r", "-t", str(config.test_apk), check=False, timeout=120)
    test_text = (test.stdout or "") + (test.stderr or "")
    if test.returncode != 0:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in test_text:
            raise SystemExit(f"Không cài được instrumentation APK:\n{test_text}")
        adb(config, "uninstall", TEST_PACKAGE, check=False, timeout=60)
        adb(config, "install", "-t", str(config.test_apk), timeout=120)

    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.CAMERA")
    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.RECORD_AUDIO")
    adb(config, "shell", "am", "force-stop", APP_ID)
    adb(config, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb(config, "shell", "wm", "dismiss-keyguard", check=False)
    adb(config, "logcat", "-c", check=False)


def run_instrumentation(config: Config, output_path: Path) -> subprocess.CompletedProcess[str]:
    command = [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "receiverHost", config.receiver_host,
        "-e", "receiverPort", str(config.receiver_port),
        "-e", "durationSeconds", str(config.duration_seconds),
        "-e", "streamBitrateMbps", str(config.stream_bitrate_mbps),
        "-e", "capabilityBitrateMbps", str(config.capability_bitrate_mbps),
        "-e", "latencyMs", str(config.latency_ms),
        "-e", "class", TEST_CLASS,
        f"{TEST_PACKAGE}/{RUNNER}",
    ]
    result = adb(config, *command, check=False, timeout=config.duration_seconds + 180)
    output_path.write_text((result.stdout or "") + (result.stderr or ""), encoding="utf-8")
    return result


def collect_preflight(config: Config, directory: Path) -> dict[str, Any]:
    result = adb(
        config,
        "shell", "run-as", APP_ID, "cat", f"files/{PREFLIGHT_FILE}",
        check=False,
    )
    text = result.stdout.strip()
    if not text:
        return {}
    (directory / PREFLIGHT_FILE).write_text(text + "\n", encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {}


def health_sampler(config: Config, path: Path, stop: threading.Event, period: int) -> None:
    with path.open("w", encoding="utf-8") as output:
        while not stop.is_set():
            stamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
            battery = adb(config, "shell", "dumpsys", "battery", check=False, timeout=20).stdout
            meminfo = adb(config, "shell", "dumpsys", "meminfo", APP_ID, check=False, timeout=20).stdout
            thermal = adb(config, "shell", "dumpsys", "thermalservice", check=False, timeout=20).stdout
            output.write(f"===== {stamp} =====\n")
            output.write("--- battery ---\n" + battery)
            output.write("--- meminfo ---\n" + meminfo)
            output.write("--- thermal ---\n" + thermal)
            output.write("\n")
            output.flush()
            stop.wait(period)


def extract_total_pss_samples(text: str) -> list[int]:
    samples: list[int] = []
    for match in re.finditer(r"TOTAL\s+PSS:\s*(\d+)", text):
        samples.append(int(match.group(1)))
    if samples:
        return samples
    for match in re.finditer(r"TOTAL\s+(\d+)\s+\d+", text):
        samples.append(int(match.group(1)))
    return samples


def validate(
    config: Config,
    instrumentation: subprocess.CompletedProcess[str],
    preflight: dict[str, Any],
    probe: dict[str, Any],
    frame_probe: dict[str, Any],
    logcat: str,
    capture_path: Path,
    health_text: str,
) -> dict[str, Any]:
    streams = probe.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), {})
    audio = next((s for s in streams if s.get("codec_type") == "audio"), {})
    fps = p3.rate_to_float(video.get("avg_frame_rate")) or p3.rate_to_float(video.get("r_frame_rate"))
    duration = p3.duration_seconds(probe)
    intervals = p3.keyframe_intervals(frame_probe)
    median_keyframe = None
    if intervals:
        ordered = sorted(intervals)
        median_keyframe = ordered[len(ordered) // 2]

    capture_mbps = None
    if duration and duration > 0 and capture_path.is_file():
        capture_mbps = capture_path.stat().st_size * 8 / duration / 1_000_000

    pss_samples = extract_total_pss_samples(health_text)
    pss_growth_kib = None
    if len(pss_samples) >= 2:
        pss_growth_kib = pss_samples[-1] - pss_samples[0]

    high_available = bool(preflight.get("highProfileAvailable", False))
    profile = str(video.get("profile") or "")
    instrumentation_text = (instrumentation.stdout or "") + (instrumentation.stderr or "")
    fatal_markers = [
        "MediaCodec encoder error",
        "Audio capture/encoder failed",
        "No hardware surface encoder",
        "FATAL EXCEPTION",
        "ANR in dev.openstream.app",
    ]

    checks = {
        "instrumentation_passed": (
            instrumentation.returncode == 0
            and "FAILURES!!!" not in instrumentation_text
            and "Process crashed" not in instrumentation_text
        ),
        "preflight_present": bool(preflight),
        "requested_4k30": (
            int(preflight.get("width", 0)) == 3840
            and int(preflight.get("height", 0)) == 2160
            and int(preflight.get("fps", 0)) == 30
        ),
        "requested_bitrate_in_phase6_range": 20 <= int(preflight.get("streamBitrateMbps", 0)) <= 40,
        "video_h264": video.get("codec_name") == "h264",
        "video_3840x2160": int(video.get("width", 0)) == 3840 and int(video.get("height", 0)) == 2160,
        "video_near_30fps": fps is not None and 28.0 <= fps <= 32.0,
        "avc_high_when_supported": (not high_available) or ("high" in profile.lower()),
        "audio_aac": audio.get("codec_name") == "aac",
        "audio_48khz": str(audio.get("sample_rate") or "") == "48000",
        "sample_duration_reached": duration is not None and duration >= config.duration_seconds * 0.90,
        "keyframe_near_2_seconds": median_keyframe is not None and 1.4 <= median_keyframe <= 2.6,
        "hardware_encoder_logged": "Using hardware encoder" in logcat,
        "microphone_started": "Using audio source" in logcat,
        "no_known_fatal_runtime_error": not any(marker in logcat for marker in fatal_markers),
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
            },
            "audio": {
                "codec": audio.get("codec_name"),
                "sampleRate": audio.get("sample_rate"),
                "channels": audio.get("channels"),
            },
            "soak": {
                "requestedDurationSeconds": config.duration_seconds,
                "capturedDurationSeconds": duration,
                "requestedBitrateMbps": config.stream_bitrate_mbps,
                "captureAverageMbps": capture_mbps,
                "medianKeyframeIntervalSeconds": median_keyframe,
                "healthSampleCount": health_text.count("===== "),
                "pssFirstKiB": pss_samples[0] if pss_samples else None,
                "pssLastKiB": pss_samples[-1] if pss_samples else None,
                "pssGrowthKiB": pss_growth_kib,
            },
        },
        "performanceGate": {
            "included": False,
            "reason": (
                "Harness records requested/observed bitrate and long-run health. "
                "Issue #17 still requires confirming this run used a suitable LAN/Wi-Fi path before it counts as the 20-40 Mbps performance acceptance."
            ),
        },
    }


def main(argv: list[str]) -> int:
    config = parse_args(argv)
    p3.require_binary("adb")
    ffmpeg = p3.require_binary("ffmpeg")
    ffprobe = p3.require_binary("ffprobe")
    p3.ensure_srt(ffmpeg)

    config.evidence_dir.mkdir(parents=True, exist_ok=True)
    capture_path = config.evidence_dir / "phase6-soak.ts"
    receiver_log = config.evidence_dir / "receiver-ffmpeg.log"
    instrumentation_log = config.evidence_dir / "instrumentation.txt"
    health_log = config.evidence_dir / "device-health-samples.txt"

    p3.connect_device(config)  # type: ignore[arg-type]
    install_with_signature_recovery(config)

    receiver, receiver_log_file = p3.start_receiver(  # type: ignore[arg-type]
        config, ffmpeg, capture_path, receiver_log
    )
    stop_health = threading.Event()
    sampler = threading.Thread(
        target=health_sampler,
        args=(config, health_log, stop_health, config.sample_period_seconds),
        daemon=True,
    )
    sampler.start()
    try:
        instrumentation = run_instrumentation(config, instrumentation_log)
    finally:
        stop_health.set()
        sampler.join(timeout=30)
        p3.stop_receiver(receiver, receiver_log_file)

    logcat = adb(config, "logcat", "-d", "-v", "threadtime", check=False).stdout
    (config.evidence_dir / "device-logcat.txt").write_text(logcat, encoding="utf-8")
    preflight = collect_preflight(config, config.evidence_dir)
    health_text = health_log.read_text(encoding="utf-8", errors="replace") if health_log.exists() else ""

    probe: dict[str, Any] = {}
    frame_probe: dict[str, Any] = {}
    if capture_path.is_file() and capture_path.stat().st_size > 0:
        probe = p3.ffprobe_json(ffprobe, capture_path, config.evidence_dir / "ffprobe.json")
        frame_probe = p3.ffprobe_frames(ffprobe, capture_path, config.evidence_dir / "ffprobe-frames.json")
    else:
        (config.evidence_dir / "ffprobe.json").write_text("{}\n", encoding="utf-8")
        (config.evidence_dir / "ffprobe-frames.json").write_text("{}\n", encoding="utf-8")

    acceptance = validate(
        config,
        instrumentation,
        preflight,
        probe,
        frame_probe,
        logcat,
        capture_path,
        health_text,
    )
    (config.evidence_dir / "acceptance.json").write_text(
        json.dumps(acceptance, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(acceptance, indent=2, ensure_ascii=False))
    if not acceptance["passed"]:
        failed = [name for name, passed in acceptance["checks"].items() if not passed]
        print("FAILED checks:", ", ".join(failed), file=sys.stderr)
        return 1
    print("Phase 6 soak functional gate: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
