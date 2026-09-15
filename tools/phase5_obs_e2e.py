#!/usr/bin/env python3
"""Nghiệm thu Giai đoạn 5 bằng Android thật + nguồn OpenStream trong OBS thật.

Harness không chạy FFmpeg receiver. OBS phải đang chạy, đã tạo một nguồn
OpenStream và đang lắng nghe receiver_port. Harness sẽ phát 4K30 + micro từ
Android hai lần liên tiếp, rồi đọc log OBS để xác nhận media thật đã đi qua
plugin và cơ chế reconnect hoạt động.
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
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

APP_ID = "dev.openstream.app"
TEST_PACKAGE = "dev.openstream.app.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.openstream.app.Phase3DeviceE2eTest#stream4k30WithMicrophoneToSrtReceiver"
PREFLIGHT_FILE = "phase3-device-e2e-preflight.json"

RECEIVE_RE = re.compile(
    r"\[OpenStream\] Receiving (?P<width>\d+)x(?P<height>\d+) video stream "
    r"codec=(?P<codec>\S+)(?P<audio> \+ audio)?"
)
AUDIO_RE = re.compile(
    r"\[OpenStream\] Opened audio decoder: (?P<codec>[^,]+), "
    r"(?P<rate>\d+) Hz, (?P<channels>\d+) channels"
)
OUTPUT_RE = re.compile(
    r"\[OpenStream\] Output (?P<count>\d+) decoded (?:YUV|BGRA) frame\(s\) "
    r"to OBS \((?P<width>\d+)x(?P<height>\d+),"
)
RECONNECT_RE = re.compile(r"\[OpenStream\] Holding .+ for reconnect")
PLUGIN_LOADED = "[OpenStream] OBS plugin loaded"


@dataclass(frozen=True)
class Config:
    adb_serial: str
    receiver_host: str
    obs_log: Path
    app_apk: Path
    test_apk: Path
    receiver_port: int
    duration_seconds: int
    stream_bitrate_mbps: int
    capability_bitrate_mbps: int
    latency_ms: int
    reconnect_gap_seconds: int
    evidence_dir: Path


def require_binary(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Thiếu chương trình bắt buộc trên PATH: {name}")
    return path


def run(command: list[str], *, check: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(command), flush=True)
    result = subprocess.run(command, check=False, timeout=timeout, text=True, capture_output=True)
    if check and result.returncode != 0:
        if result.stdout:
            print(result.stdout.rstrip(), flush=True)
        if result.stderr:
            print(result.stderr.rstrip(), file=sys.stderr, flush=True)
        raise subprocess.CalledProcessError(
            result.returncode,
            command,
            output=result.stdout,
            stderr=result.stderr,
        )
    return result


def adb(config: Config, *args: str, check: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    return run(["adb", "-s", config.adb_serial, *args], check=check, timeout=timeout)


def parse_args(argv: list[str]) -> Config:
    parser = argparse.ArgumentParser(
        description="Nghiệm thu Giai đoạn 5 bằng Android thật + OBS thật, không dùng FFmpeg receiver.",
    )
    parser.add_argument("--adb-serial", default=os.environ.get("OPENSTREAM_ADB_SERIAL"))
    parser.add_argument("--receiver-host", default=os.environ.get("OPENSTREAM_RECEIVER_HOST"))
    parser.add_argument("--obs-log", type=Path, default=os.environ.get("OPENSTREAM_OBS_LOG"))
    parser.add_argument("--app-apk", type=Path, default=Path("dist/openstream-android.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("dist/openstream-android-test.apk"))
    parser.add_argument("--receiver-port", type=int, default=9000)
    parser.add_argument("--duration-seconds", type=int, default=12)
    parser.add_argument("--stream-bitrate-mbps", type=int, default=8)
    parser.add_argument("--capability-bitrate-mbps", type=int, default=30)
    parser.add_argument("--latency-ms", type=int, default=120)
    parser.add_argument("--reconnect-gap-seconds", type=int, default=4)
    parser.add_argument("--evidence-dir", type=Path, default=Path("build/phase5-obs-e2e"))
    args = parser.parse_args(argv)

    if not args.adb_serial:
        parser.error("cần --adb-serial hoặc OPENSTREAM_ADB_SERIAL")
    if not args.receiver_host:
        parser.error("cần --receiver-host hoặc OPENSTREAM_RECEIVER_HOST")
    if not args.obs_log:
        parser.error("cần --obs-log hoặc OPENSTREAM_OBS_LOG")
    if not 1 <= args.receiver_port <= 65535:
        parser.error("--receiver-port phải nằm trong 1..65535")
    if not 8 <= args.duration_seconds <= 120:
        parser.error("--duration-seconds phải nằm trong 8..120")
    if not 8 <= args.stream_bitrate_mbps <= 50:
        parser.error("--stream-bitrate-mbps phải nằm trong 8..50")
    if not 8 <= args.capability_bitrate_mbps <= 50:
        parser.error("--capability-bitrate-mbps phải nằm trong 8..50")
    if not 20 <= args.latency_ms <= 10_000:
        parser.error("--latency-ms phải nằm trong 20..10000")
    if not 1 <= args.reconnect_gap_seconds <= 30:
        parser.error("--reconnect-gap-seconds phải nằm trong 1..30")

    return Config(
        adb_serial=args.adb_serial,
        receiver_host=args.receiver_host,
        obs_log=Path(args.obs_log),
        app_apk=args.app_apk,
        test_apk=args.test_apk,
        receiver_port=args.receiver_port,
        duration_seconds=args.duration_seconds,
        stream_bitrate_mbps=args.stream_bitrate_mbps,
        capability_bitrate_mbps=args.capability_bitrate_mbps,
        latency_ms=args.latency_ms,
        reconnect_gap_seconds=args.reconnect_gap_seconds,
        evidence_dir=args.evidence_dir,
    )


def connect_device(config: Config) -> None:
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

    # GitHub-hosted runners tạo debug keystore tạm thời. Một APK debug còn lại
    # trên thiết bị từ run trước có thể mang chữ ký khác và khiến `install -r`
    # bị INSTALL_FAILED_UPDATE_INCOMPATIBLE. Gỡ đúng hai package test trước khi
    # cài build của run hiện tại để nghiệm thu luôn dùng APK vừa build.
    adb(config, "shell", "am", "force-stop", APP_ID, check=False)
    adb(config, "uninstall", TEST_PACKAGE, check=False, timeout=30)
    adb(config, "uninstall", APP_ID, check=False, timeout=30)

    # Với ADB qua Tailscale, --no-streaming ổn định hơn vì APK được đẩy xong
    # trước khi Package Manager bắt đầu cài đặt.
    adb(config, "install", "--no-streaming", "-t", str(config.app_apk), timeout=180)
    adb(config, "install", "--no-streaming", "-t", str(config.test_apk), timeout=180)
    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.CAMERA")
    adb(config, "shell", "pm", "grant", APP_ID, "android.permission.RECORD_AUDIO")
    adb(config, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb(config, "shell", "wm", "dismiss-keyguard", check=False)


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
    result = adb(config, *command, check=False, timeout=config.duration_seconds + 90)
    output_path.write_text((result.stdout or "") + (result.stderr or ""), encoding="utf-8")
    return result


def instrumentation_passed(result: subprocess.CompletedProcess[str]) -> bool:
    text = (result.stdout or "") + (result.stderr or "")
    return (
        result.returncode == 0
        and "FAILURES!!!" not in text
        and "INSTRUMENTATION_FAILED" not in text
        and "Process crashed" not in text
    )


def collect_preflight(config: Config) -> dict[str, Any]:
    result = adb(
        config,
        "shell",
        "run-as",
        APP_ID,
        "cat",
        f"files/{PREFLIGHT_FILE}",
        check=False,
    )
    text = result.stdout.strip()
    if not text:
        return {}
    (config.evidence_dir / PREFLIGHT_FILE).write_text(text + "\n", encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {}


def parse_obs_log(text: str, *, require_reconnect: bool = True) -> dict[str, Any]:
    receives: list[dict[str, Any]] = []
    audios: list[dict[str, Any]] = []
    outputs: list[dict[str, Any]] = []

    for match in RECEIVE_RE.finditer(text):
        receives.append({
            "position": match.start(),
            "width": int(match.group("width")),
            "height": int(match.group("height")),
            "codec": match.group("codec").lower(),
            "audio": bool(match.group("audio")),
        })
    for match in AUDIO_RE.finditer(text):
        audios.append({
            "position": match.start(),
            "codec": match.group("codec").strip().lower(),
            "sampleRate": int(match.group("rate")),
            "channels": int(match.group("channels")),
        })
    for match in OUTPUT_RE.finditer(text):
        outputs.append({
            "position": match.start(),
            "count": int(match.group("count")),
            "width": int(match.group("width")),
            "height": int(match.group("height")),
        })
    reconnect_positions = [m.start() for m in RECONNECT_RE.finditer(text)]

    four_k_receives = [
        item for item in receives
        if item["width"] == 3840
        and item["height"] == 2160
        and item["codec"] == "h264"
        and item["audio"]
    ]
    audio_ok = any(
        item["codec"] == "aac" and item["sampleRate"] == 48_000 and item["channels"] >= 1
        for item in audios
    )
    output_ok = any(item["width"] == 3840 and item["height"] == 2160 and item["count"] >= 1 for item in outputs)

    reconnect_ok = True
    if require_reconnect:
        reconnect_ok = False
        if len(four_k_receives) >= 2:
            first = four_k_receives[0]["position"]
            second = four_k_receives[1]["position"]
            reconnect_ok = any(first < pos < second for pos in reconnect_positions)

    gates = {
        "pluginLoaded": PLUGIN_LOADED in text,
        "two4k30H264AudioSessions": len(four_k_receives) >= 2,
        "aac48kAudioDecoded": audio_ok,
        "decoded4kFrameOutputToObs": output_ok,
        "reconnectObservedBetweenSessions": reconnect_ok,
    }
    return {
        "passed": all(gates.values()),
        "gates": gates,
        "receives": receives,
        "audioDecoders": audios,
        "videoOutputs": outputs,
        "reconnectPositions": reconnect_positions,
    }


def main(argv: list[str]) -> int:
    config = parse_args(argv)
    require_binary("adb")
    config.evidence_dir.mkdir(parents=True, exist_ok=True)

    if not config.obs_log.is_file():
        raise SystemExit(f"Không tìm thấy log OBS: {config.obs_log}")
    full_before = config.obs_log.read_text(encoding="utf-8", errors="replace")
    if PLUGIN_LOADED not in full_before:
        raise SystemExit("Log OBS chưa cho thấy plugin OpenStream đã được nạp")
    log_offset = config.obs_log.stat().st_size

    connect_device(config)
    install_test_build(config)
    adb(config, "logcat", "-c", check=False)

    first = run_instrumentation(config, config.evidence_dir / "instrumentation-1.txt")
    time.sleep(config.reconnect_gap_seconds)
    second = run_instrumentation(config, config.evidence_dir / "instrumentation-2.txt")
    time.sleep(2)

    preflight = collect_preflight(config)
    adb(config, "shell", "am", "force-stop", APP_ID, check=False)
    device_log = adb(config, "logcat", "-d", "-v", "threadtime", check=False).stdout
    (config.evidence_dir / "device-logcat.txt").write_text(device_log, encoding="utf-8")

    current_size = config.obs_log.stat().st_size
    with config.obs_log.open("rb") as handle:
        handle.seek(log_offset if current_size >= log_offset else 0)
        obs_tail = handle.read().decode("utf-8", errors="replace")
    # Giữ marker plugin-loaded trong evidence ngay cả khi nó nằm trước offset phiên test.
    obs_evidence = PLUGIN_LOADED + "\n" + obs_tail
    (config.evidence_dir / "obs-session.log").write_text(obs_evidence, encoding="utf-8")

    parsed = parse_obs_log(obs_evidence, require_reconnect=True)
    sender_4k30 = (
        int(preflight.get("width", 0)) == 3840
        and int(preflight.get("height", 0)) == 2160
        and int(preflight.get("fps", 0)) == 30
        and int(preflight.get("capabilityBitrateMbps", 0)) == config.capability_bitrate_mbps
    )
    android_gates = {
        "instrumentation1Passed": instrumentation_passed(first),
        "instrumentation2Passed": instrumentation_passed(second),
        "sender4k30Capability": sender_4k30,
        "hardwareVideoEncoderLogged": "Using hardware encoder" in device_log,
        "microphoneCaptureStarted": "Using audio source" in device_log,
        "noVideoEncoderFailure": (
            "MediaCodec encoder error" not in device_log
            and "No hardware surface encoder" not in device_log
        ),
    }
    parsed["androidGates"] = android_gates
    parsed["preflight"] = preflight
    parsed["passed"] = bool(parsed["passed"] and all(android_gates.values()))
    parsed["config"] = {
        **asdict(config),
        "obs_log": str(config.obs_log),
        "app_apk": str(config.app_apk),
        "test_apk": str(config.test_apk),
        "evidence_dir": str(config.evidence_dir),
    }

    acceptance_path = config.evidence_dir / "acceptance.json"
    acceptance_path.write_text(json.dumps(parsed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(parsed, indent=2, ensure_ascii=False))
    return 0 if parsed["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))