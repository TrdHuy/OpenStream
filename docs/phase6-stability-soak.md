# Giai đoạn 6: ổn định dài hạn, telemetry và soak

Giai đoạn 6 thuộc #8. Mục tiêu là tách rõ **telemetry/runtime recovery trong mã nguồn** khỏi **bằng chứng chạy dài vật lý**.

## Telemetry runtime

`SrtStreamClient` duy trì hai nhóm số liệu:

- số liệu phiên hiện tại: video access unit, keyframe, video byte, audio access unit, audio byte, lỗi gửi và PTS cuối;
- số liệu vòng đời tiến trình: tổng access unit/byte, số kết nối thành công, số reconnect và số lần mất kết nối.

Một generation transport chỉ được tính mất kết nối một lần dù video và audio cùng phát hiện lỗi. Khi reconnect, counter của phiên mới được reset nhưng counter vòng đời không bị xóa.

`SendRateMeter` tính bitrate gửi thực tế từ chênh lệch counter byte vòng đời theo đồng hồ monotonic và chỉ giữ một mẫu trước đó, không tạo queue telemetry không giới hạn.

`TelemetrySampler` thu pin, RSSI Wi‑Fi, nhiệt độ pin và Android thermal status khi hệ điều hành cung cấp.

## Soak E2E

Instrumentation `Phase6SoakE2eTest` giữ **một phiên liên tục** 3840×2160@30 + micro. Thời lượng mặc định là 1800 giây và có thể đặt từ 60 đến 3600 giây. Bitrate Phase 6 được giới hạn 20–40 Mbps; mặc định 30 Mbps.

Host harness:

```bash
python3 tools/phase6_soak_e2e.py \
  --adb-serial 'DEVICE:PORT' \
  --receiver-host 'RECEIVER_IP' \
  --app-apk dist/openstream-android.apk \
  --test-apk dist/openstream-android-test.apk \
  --duration-seconds 1800 \
  --stream-bitrate-mbps 30
```

Harness mở FFmpeg SRT listener, chạy instrumentation, lấy `ffprobe`, `logcat`, preflight và định kỳ lấy `dumpsys battery`, `dumpsys meminfo` và `dumpsys thermalservice`.

Evidence mặc định ở `build/phase6-soak-e2e/` gồm `acceptance.json`, MPEG-TS capture, ffprobe, instrumentation output, device logcat và các mẫu sức khỏe thiết bị.

## GitHub Actions

Workflow `Phase 6 Soak E2E` chạy job build trên GitHub-hosted runner, sau đó chuyển APK sang runner `[self-hosted, linux, openstream-device-lab]`.

`adb_serial` và `receiver_host` là override tùy chọn. Nếu bỏ trống, workflow yêu cầu đúng một ADB device ở trạng thái `device` và tự lấy `tailscale ip -4` của runner. Không hard-code IP/ADB serial vào repository.

## Quy tắc nghiệm thu

`acceptance.json` PASS chứng minh soak chức năng: phiên đạt đủ thời lượng, video H.264 4K30, AAC 48 kHz, profile High khi capability báo hỗ trợ, keyframe gần 2 giây, hardware encoder và micro thật đã khởi động, không có lỗi runtime nghiêm trọng đã biết.

PASS tự động **không tự động hoàn tất gate hiệu năng 20–40 Mbps**. #17 chỉ được đóng khi xác nhận bài chạy được thực hiện trên LAN/Wi‑Fi phù hợp và evidence cho thấy chất lượng/độ ổn định đáp ứng mục tiêu. Vì vậy #8 vẫn mở cho tới khi cả phần mã nguồn và evidence vật lý đều hoàn tất.
