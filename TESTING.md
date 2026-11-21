# Checklist kiểm thử

Chỉ đánh dấu `[x]` sau khi test tương ứng chạy xong và đạt. Kết quả dưới đây được ghi theo lần chạy ngày 17/11/2026 trong môi trường hiện tại.

## Đã chạy đạt

- [x] **Mở và nối tiếp journal của Master trên Windows** — 3 test trong `MasterStateStoreTest`: tạo journal mới, mở lại journal có newline và sửa newline cuối trước khi ghi tiếp.
- [x] **Hai máy logic cùng đăng ký vào một Master** — `MultiMachineWebManagementTest`: hai worker có `machineId` khác nhau được hiển thị trong hai nhóm và cùng tính vào tổng Worker.
- [x] **Đổi bí danh nhưng giữ nguyên định danh Worker** — test API đổi tên Worker B bằng tiếng Việt, đọc lại snapshot xác nhận tên mới và tổng/ID không đổi.
- [x] **Điều khiển Worker từ máy/ngữ cảnh web khác** — cùng test tạm dừng và bật lại Worker B qua REST API, kiểm tra trạng thái Worker và snapshot.
- [x] **Kiểm soát nguồn gọi API từ trình duyệt** — cùng test xác nhận origin cùng hostname được phép và origin host khác bị từ chối; tên rỗng trả HTTP 400.
- [x] **Giám sát tiến trình Worker trên Windows** — `SupervisorLeaseTest` kiểm tra metadata cạnh file khóa và từ chối lease sau khi nhả khóa.
- [x] **Chọn đúng API cho trình duyệt dùng IP Radmin** — 5 Node test xác nhận API lấy hostname từ URL, giữ localhost cho demo cục bộ, hỗ trợ override và tạo mã job khi `randomUUID()` không có.
- [x] **Build Web UI** — `npm run build` hoàn tất với Vite 8.3.4 sau khi bổ sung mã job dùng được qua HTTP trên mạng riêng.
- [x] **Chạy script Master/Worker thật qua Radmin adapter** — Master chạy trên cổng 5000/API 8080; hai nhóm Worker do `start-lan-workers.ps1` quản lý đều báo `REGISTERED 1/1` và tiếp tục sống sau khi đăng ký.
- [x] **Giao diện thật qua IP Radmin trên máy A** — mở `http://<IP_A_RADMIN>:5173`, thấy Master ONLINE và hai nhóm Worker qua adapter Radmin hiện hành.
- [x] **Đổi tên và tạm dừng Worker qua giao diện** — đổi Worker B thành `Worker B demo`, giữ ID `ui-demo-b-worker-01`, tạm dừng rồi bật lại; tổng Worker vẫn là 2.
- [x] **Tính toán từ UI qua hai Worker** — phép nhân ma trận 3×3 hoàn tất với kết quả `[[30,24,18],[84,69,54],[138,114,90]]`; giao diện liệt kê `ui-demo-a-worker-01` và `ui-demo-b-worker-01` là hai Worker đã trả kết quả.
- [x] **Phép tính và luồng job** — toàn bộ Java test đạt, gồm ma trận, đồ thị, job web, dữ liệu kết quả, lỗi đầu vào, timeout và worker pause/offline.
- [x] **Đăng ký, giới hạn tài nguyên, lập lịch và phục hồi** — toàn bộ Java test đạt, gồm heartbeat, reconnect, khởi động lại Master/Worker, phân phối Worker và mô phỏng 50 Worker logic trên mỗi máy.
- [x] **Build sạch trên Windows sau khi dừng tiến trình cũ** — `mvn clean package` hoàn tất; **65 test, 0 lỗi, 0 thất bại, 0 bỏ qua**; JAR chạy được được tạo lại.
- [x] **Toàn bộ frontend test** — `npm test`: **5 test đạt, 0 thất bại**.

## Chưa thể đánh dấu — cần hai laptop thật

- [ ] Hai máy A/B ping qua Radmin VPN và kiểm tra TCP hai chiều.
- [ ] Worker chạy trên laptop B vật lý đăng ký qua Radmin vào Master A; xác nhận log `REGISTERED` và tổng A+B trên snapshot.
- [ ] Mở giao diện từ trình duyệt ở laptop B vật lý qua IP Radmin A và quản lý worker/job cùng lúc từ hai máy thật.
- [ ] Đổi tên từ một laptop và xác nhận laptop còn lại thấy cập nhật.
- [ ] Tạm dừng Worker B từ A, bật lại từ B; xác nhận A và B cùng thấy trạng thái mới.
- [ ] Chạy job qua hai máy thật và kiểm tra Worker từ A lẫn B có đóng góp kết quả đúng.

Trong phiên này chỉ có một laptop và adapter Radmin VPN; hai nhóm Worker logic cùng chạy trên laptop đó và dùng chung địa chỉ adapter. Vì vậy, các lượt kiểm tra trên xác nhận UI/API, script supervisor và luồng tính toán qua card Radmin của máy A, nhưng không chứng minh đường mạng giữa hai máy vật lý. Những mục A↔B thật vẫn để trống cho đến khi chạy theo [LAN.md](LAN.md); đây là phần nghiệm thu còn thiếu trước khi kết luận demo Radmin A↔B hoàn chỉnh.

## Chạy lại

Từ thư mục dự án:

```powershell
mvn clean package
cd frontend
npm test
npm run build
```

Kiểm thử A↔B thật phải chạy trên hai laptop có cùng mạng Radmin; làm theo các bước và lệnh chẩn đoán trong [LAN.md](LAN.md).
