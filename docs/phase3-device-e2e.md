# Nghiệm thu E2E Giai đoạn 3 trên thiết bị thật

Mục tiêu của bài kiểm thử này là tự động xác nhận đường chức năng:

`Camera2 thật -> MediaCodec H.264 phần cứng -> mic/AudioRecord -> AAC -> MPEG-TS -> libsrt/SRT -> Linux receiver -> ffprobe`.

## Mô hình lab

Lab dùng hai thiết bị độc lập, không cần cắm USB trực tiếp:

- **T1**: Android thật, truy cập được từ T2 bằng ADB qua Tailscale;
- **T2**: Linux self-hosted GitHub Actions runner, đồng thời là SRT receiver và máy thu evidence.

T2 chủ động kết nối outbound tới GitHub để nhận job. GitHub không cần biết IP của T2 và không SSH vào T2.

Workflow dùng repository variable:

```text
OPENSTREAM_ANDROID_ADB_ENDPOINT
```

Giá trị là ADB endpoint của T1, ví dụ `100.x.y.z:5555`. Endpoint này không được hard-code trong repository.

Tailscale IPv4 của T2 được workflow tự lấy tại runtime bằng:

```bash
tailscale ip -4
```

Sau đó workflow tự chạy `adb connect "$OPENSTREAM_ANDROID_ADB_ENDPOINT"`, xác nhận trạng thái `device`, rồi truyền Tailscale IPv4 của T2 cho instrumentation làm SRT caller target.

## Phân tách capability và hiệu năng mạng

Đường Tailscale có thể chậm hơn bitrate sản phẩm. Vì vậy bài kiểm thử tách hai gate:

- **Capability gate** luôn kiểm tra Camera2 + hardware MediaCodec ở `3840x2160 @ 30 fps` với bitrate mục tiêu mặc định `30 Mbps`.
- **Network E2E gate** có thể phát 4K30 ở bitrate thấp nhất hiện tại của Phase 3, mặc định `8 Mbps`, để kiểm chứng luồng thật qua SRT mà không biến tốc độ Tailscale thành điều kiện thất bại giả.

Hiệu năng sustained `20-40 Mbps` trên LAN/Wi-Fi tốt vẫn là hạng mục nghiệm thu riêng. Không được dùng kết quả Tailscale để kết luận throughput 4K30 production.

## Điều kiện Linux T2

T2 cần có:

- GitHub self-hosted runner có label `openstream-device-lab`;
- Python 3;
- `adb` trên `PATH`;
- `tailscale` trên `PATH`;
- FFmpeg có hỗ trợ `srt`;
- `ffprobe`;
- kết nối Tailscale tới Android;
- cổng UDP `19000` không bị firewall chặn.

Kiểm tra nhanh:

```bash
adb version
tailscale ip -4
ffmpeg -hide_banner -protocols | grep -w srt
ffprobe -version | head -n 1
```

Runner nên chạy bằng cùng user đã xác nhận `adb connect` tới T1 hoạt động.

## Điều kiện Android T1

Android phải cho phép ADB từ Linux qua Tailscale. Pairing/authorization ban đầu phải hoàn thành trước khi workflow chạy.

Ví dụ khi thiết bị dùng ADB TCP cổng 5555:

```bash
adb connect <TAILSCALE_IP_ANDROID>:5555
adb -s <TAILSCALE_IP_ANDROID>:5555 get-state
```

Nếu dùng Wireless debugging với cổng ngẫu nhiên, repository variable `OPENSTREAM_ANDROID_ADB_ENDPOINT` phải chứa đúng endpoint hiện tại.

## Chạy trực tiếp trên Linux

Sau khi có `openstream-android.apk` và `openstream-android-test.apk`:

```bash
python3 tools/phase3_device_e2e.py \
  --adb-serial <TAILSCALE_IP_ANDROID>:5555 \
  --receiver-host <TAILSCALE_IP_LINUX> \
  --app-apk dist/openstream-android.apk \
  --test-apk dist/openstream-android-test.apk \
  --stream-bitrate-mbps 8 \
  --capability-bitrate-mbps 30 \
  --duration-seconds 15 \
  --latency-ms 2000
```

Có thể dùng biến môi trường thay cho hai tham số địa chỉ:

```bash
export OPENSTREAM_ADB_SERIAL=<TAILSCALE_IP_ANDROID>:5555
export OPENSTREAM_RECEIVER_HOST=<TAILSCALE_IP_LINUX>
python3 tools/phase3_device_e2e.py
```

## Evidence tự động

Mặc định evidence được ghi tại `build/phase3-device-e2e/`:

- `acceptance.json`: kết quả pass/fail từng điều kiện;
- `phase3-device-e2e-preflight.json`: capability Camera2/MediaCodec từ chính Android;
- `ffprobe.json`: codec/resolution/fps/audio thực nhận;
- `ffprobe-frames.json`: evidence keyframe cadence;
- `device-logcat.txt`: encoder/audio/camera runtime log;
- `receiver-ffmpeg.log`: log phía Linux receiver;
- `instrumentation.txt`: kết quả Android instrumentation;
- `phase3-e2e.ts`: mẫu MPEG-TS nhận qua SRT.

Bài kiểm thử pass khi tối thiểu xác nhận được:

- main back camera có đường Camera2 + hardware AVC `3840x2160@30` tại capability bitrate 30 Mbps;
- stream thật nhận được là H.264 `3840x2160` gần 30 fps;
- High profile được yêu cầu và ffprobe thấy High khi capability công bố hỗ trợ;
- log xác nhận hardware video encoder, không có software video fallback;
- có AAC 48 kHz và `AudioRecord` thật đã bắt đầu thu microphone;
- keyframe cadence gần 2 giây;
- app giữ trạng thái LIVE trong suốt sample;
- receiver thực nhận được MPEG-TS qua SRT.

## GitHub Actions

Workflow `.github/workflows/phase3-device-e2e.yml` dùng runner:

```text
self-hosted, linux, openstream-device-lab
```

APK và instrumentation APK được build trên GitHub-hosted runner. Job hardware sau đó tải chúng xuống T2, tự resolve topology T1/T2, chạy test và upload evidence.

Trong nhánh Phase 3, workflow có `push` trigger giới hạn vào nhánh `phase-3-4k30-mic-path` và các path liên quan để có thể chạy acceptance ngay khi harness/app thay đổi. `workflow_dispatch` vẫn được giữ cho các lần chạy lại thủ công với bitrate, latency hoặc duration khác.

Phiếu #5 chỉ được đóng sau khi hardware E2E thực tế PASS và hạng mục throughput LAN thủ công được xác nhận hoặc được tách rõ thành acceptance gate riêng theo quyết định dự án.
