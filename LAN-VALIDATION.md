# Kết quả xác minh LAN — 16/10/2026

## Đã xác minh trên laptop hiện tại

- `mvn package`: thành công; Java 25.0.2, target Java 21.
- 32 tests, 0 failures, 0 errors, 0 skipped.
- Các nhóm: tính ma trận/đồ thị hiện có; chia dòng không đều; CPU/RAM/heap và hysteresis; giữ chỗ capacity đồng thời; mất Worker; hàng đợi khi chưa có Worker; timeout; giới hạn ba lần thử; kết quả lỗi/cũ/trùng; Worker chạy trước Master; Master/Worker khởi động lại; lỗi kết nối ngược; ID trùng; tạm dừng; mất ACK nhận việc/kết quả; từ chối giao thức cũ.
- Ba script LAN được kiểm tra cú pháp bằng PowerShell Parser.
- JAR chạy bằng ba tiến trình Java riêng (Master benchmark + hai Worker) qua loopback. Cả hai Worker hoàn thành 8 tác vụ sau warmup và lượt đo; kết quả đúng.

## Smoke benchmark localhost (không phải đo LAN)

Dữ liệu 32×32, seed 42, RESOURCE_AWARE, một lượt warmup, một lượt đo, mỗi Worker capacity=1:

| Tuần tự | Phân tán | Speedup | Sai số tuyệt đối lớn nhất |
|---|---|---|---|
| 2.003 ms | 442.075 ms | 0.0045× | 0 |

Bài toán nhỏ, trên cùng laptop; truyền JSON, socket, lập lịch và logging chiếm chi phí lớn. Không dùng số này để khẳng định hiệu năng hai laptop. Smoke test được chạy trước chỉnh sửa cuối về thông báo số task và xử lý lỗi mở cổng; toàn bộ 32 tests được chạy lại thành công trên bản cuối.

## Chưa xác minh

- IP, firewall và Wi-Fi thực tế của laptop B.
- Tốc độ khi chỉ A, chỉ B và A+B trên cùng LAN.

Dùng checklist và LanBenchmarkApp trong LAN.md để nghiệm thu. Không có thay đổi firewall nào được thực hiện tự động.

## Ghi chú môi trường

Sandbox chặn socket localhost của Java nên các bài kiểm thử mạng được chạy ngoài sandbox. Một lượt đóng gói gặp khóa file trạng thái Maven của Windows; lượt chạy lại hoàn thành thành công.
