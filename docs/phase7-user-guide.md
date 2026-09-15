# OpenStream — hướng dẫn sử dụng hằng ngày (Giai đoạn 7)

Mục tiêu của luồng này là để người dùng bình thường có thể cấu hình điện thoại và OBS mà không cần terminal hay lệnh CLI.

## 1. Cài ứng dụng Android

1. Cài `openstream-android.apk` trên điện thoại Android.
2. Mở OpenStream và cấp quyền Camera + Microphone.
3. Mở **Cấu hình phát**.

## 2. Chọn camera và preset

Trong phần **Camera và mã hóa**:

1. Chọn camera/lens mà thiết bị thực sự cung cấp.
2. Chọn preset phù hợp: `1080p30`, `1080p60`, `4K30` hoặc `4K60`.
3. Preset có dấu `✓` đã được Camera2 + bộ mã hóa H.264 phần cứng trên chính thiết bị xác nhận.
4. Preset hiện `Không hỗ trợ` không được dùng; dòng mô tả bên dưới nêu rõ mode/bitrate/lens không vượt qua capability check.
5. Có thể chỉnh bitrate, bitrate mode, AVC profile, B-frame và keyframe interval sau khi chọn preset. Trước khi lưu/kết nối, ứng dụng kiểm lại cấu hình với capability thật của máy.

Preset là giá trị khởi đầu thuận tiện, không phải cam kết hiệu năng mạng. 4K30/4K60 ở bitrate cao vẫn cần LAN/Wi-Fi đủ tốt để nghiệm thu production.

## 3. Âm thanh

Trong phần **Âm thanh**:

- bật/tắt microphone;
- chọn sample rate;
- chọn số kênh;
- chọn audio bitrate.

Cấu hình mặc định ưu tiên AAC 48 kHz cho OBS.

## 4. Kết nối SRT

Trong phần **Kết nối SRT**:

- `Tên máy hoặc địa chỉ IP của OBS`: địa chỉ PC chạy OBS;
- `Cổng OBS`: mặc định `9000`;
- `Độ trễ SRT`: mặc định phù hợp để bắt đầu là `120 ms`;
- `Cổng lắng nghe`: dùng cho listener mode khi cần.

Bấm **Lưu và kết nối** để ứng dụng kiểm cấu hình rồi kết nối. Host/port/latency không hợp lệ sẽ bị chặn ngay tại màn hình cấu hình.

Manual unicast là đường dự phòng hợp lệ khi discovery không khả dụng. Discovery/pairing tự động phải được nghiệm thu trên cùng LAN/Wi-Fi thật; không dùng kết quả Tailscale để tuyên bố gate này đã đạt.

## 5. Dùng profile

Phần **Profile** lưu đồng thời cấu hình video/audio và endpoint kết nối.

- **Mới**: bắt đầu một profile mới từ cấu hình đang hiển thị.
- Nhập tên profile, ví dụ `Studio 4K30`.
- **Lưu**: tạo mới hoặc cập nhật profile đang chọn.
- **Dùng**: áp dụng lại toàn bộ profile và đánh dấu nó là profile đang dùng.
- **Xóa**: xóa profile đang chọn.

Ví dụ có thể giữ riêng `Studio 4K30`, `Desk 1080p60` và `Backup 1080p30` để đổi nhanh mà không nhập lại từng trường.

## 6. Cài OBS plugin

Trên Windows x64:

1. Đóng OBS Studio.
2. Chạy `openstream-obs-plugin-installer-windows-x64.exe`.
3. Mở lại OBS.
4. Thêm source OpenStream trong danh sách Sources.

`openstream-obs-windows-x64.zip` là gói cài thủ công dự phòng; người dùng bình thường nên ưu tiên installer EXE.

## 7. Kiểm tra stream

Khi kết nối thành công, kiểm tra:

- hình ảnh xuất hiện trong source OpenStream của OBS;
- audio xuất hiện trong OBS mixer khi microphone được bật;
- độ phân giải/FPS/profile thực tế phù hợp với mode đã chọn;
- app không báo lỗi capability hoặc transport.

## 8. Khi mode không chạy

- Nếu preset ghi `Không hỗ trợ`: chọn preset thấp hơn hoặc lens khác; đây là giới hạn Camera2/MediaCodec được phát hiện trên thiết bị.
- Nếu cấu hình bị chặn lúc lưu: sửa trường có báo lỗi thay vì cố kết nối.
- Nếu manual SRT không kết nối: kiểm tra IP PC, port và firewall.
- Nếu discovery không thấy OBS: bảo đảm điện thoại và PC ở cùng LAN/Wi-Fi, tắt guest/client isolation hoặc VPN gây tách broadcast/multicast.
- Nếu 4K bị giật dù capability hợp lệ: hạ bitrate/mode và kiểm tra lại bằng LAN/Wi-Fi thật; Tailscale không dùng để nghiệm thu throughput production.

## Phạm vi nghiệm thu hiện tại

CI/remote test có thể xác nhận build, validation, profile, preset/capability, release artifacts và manual unicast functional path. Các hạng mục sau vẫn phải test thủ công trong môi trường thật trước khi đóng Giai đoạn 7:

- discovery/pairing cùng LAN;
- luồng hằng ngày không CLI dựa trên discovery thật;
- chất lượng/bitrate production;
- walkthrough release cuối cùng trên thiết bị và OBS thật;
- các gate production còn mở từ Giai đoạn 5/6.
