# Giai đoạn 0 — Xác nhận bản sao mã nguồn và nền hiện tại

Tài liệu này ghi nhận nền dùng cho kế hoạch tại issue #1 và tiêu chí xác nhận của issue #2.

## Mã nguồn nền

- Kho mã nguồn: `TrdHuy/OpenStream`
- Nhánh nền: `main`
- Commit nền: `bab07bde0b90d77b348bf36a06fa2dd98ca9ecd1`
- Nguồn gốc: fork từ `YashasVM/OpenStream`

## Điều kiện xác nhận

- Quy trình Android phải chạy đường truyền libsrt thật, không dùng `openstream.nonStreamingCiBuild=true` cho APK kiểm thử.
- Quy trình Android phải chạy kiểm thử đơn vị, lint và tạo APK gỡ lỗi.
- Quy trình OBS Windows x64 phải biên dịch và đóng gói phần mở rộng.
- Các quy trình GitHub Actions hiện có phải chạy thành công trên nhánh xác nhận này.

## Kết quả

GitHub Actions của fork đã được bật. Commit này dùng để kích hoạt lại các quy trình kiểm thử trên pull request sau khi bật Actions.

Đang chờ GitHub ghi nhận lần chạy kiểm thử của fork.
