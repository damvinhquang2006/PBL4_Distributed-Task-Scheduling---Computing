# Chạy nhiều Worker trên hai laptop

Master chỉ dùng Worker đã đăng ký trong phiên hiện tại. A có thể chạy Master và 50 Worker; B chạy thêm 50 Worker. Có thể chạy với một phần cụm; không cần chờ đủ 100 Worker.

## Chuẩn bị

- Cả hai máy dùng Java 21+ và cùng JAR mới hỗ trợ **giao thức 3**. Dừng Master/Worker cũ trước khi thay JAR. Worker giao thức 2 bị từ chối với thông báo cần cập nhật.
- Chép gói phát hành vào hai máy, giữ `scripts/` và `target/` cạnh nhau. B không cần Maven hoặc Node.js. A cần Node.js để chạy Web UI.
- Lấy IPv4 Wi-Fi từ `ipconfig`, không chọn VMware/VPN hoặc Default Gateway. Các ví dụ dùng **A=192.168.1.10, B=192.168.1.20**; thay bằng IP thật mỗi khi chuyển mạng.
- Master TCP 5000; Worker TCP 5001–5050 trên mỗi máy. Hai laptop được dùng cùng cổng vì khác IP. API 8080 và Web UI 5173 chạy trên A.
- Cổng 5040 đang bị ứng dụng khác chiếm trên máy dùng để kiểm tra. Các lệnh bên dưới chọn **5101–5150** bằng `za`; mặc định của script vẫn là 5001. Có thể chọn dải khác còn trống, không cần dừng dịch vụ đang dùng cổng đó.

## Khởi chạy

Trên A, mở PowerShell ở thư mục project/gói phát hành:

```powershell
# Terminal A1: Master
.\scripts\start-lan-master.ps1
```

```powershell
# Terminal A2: 50 Worker của A
.\scripts\start-lan-workers.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.10 -MachineId laptop-a -StartPort 5101
```

Trên B:

```powershell
.\scripts\start-lan-workers.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.20 -MachineId laptop-b -StartPort 5101
```

Mỗi máy dùng một tiến trình Java cho cụm Worker. Worker ID có dạng `laptop-a-worker-01` đến `laptop-a-worker-50`, tương tự cho B. `MachineName` mặc định là tên Windows; có thể truyền `-MachineName 'Laptop B'`.

Thông báo `opened=50/50` mới xác nhận mở cổng. Chỉ sau khi Master gọi ngược được từng Worker và trả ACK, terminal mới in:

```text
[Worker] REGISTERED laptop-b-worker-01 | laptop=laptop-b (...) | IP_B:5101 -> Master IP_A:5000
[WorkerCluster] laptop=laptop-b | REGISTERED 50/50 | opened 50/50
```

Master cũng in ID Worker, laptop và IP:cổng tương ứng. Nếu có `BIND FAILED`, kiểm tra đúng cổng đang bị chiếm; launcher báo số mở/đăng ký thực tế, không tự tạo Worker thay thế.

Trên A, mở terminal thứ ba:

```powershell
.\scripts\start-ui.ps1
```

Mở `http://localhost:5173`, chọn **Giám Sát Cụm**. UI lấy snapshot `/api/cluster` khi mở/reload và mỗi 2,5 giây: mỗi laptop có nhóm riêng, số kết nối/sẵn sàng/đang xử lý, CPU/RAM một lần cho cả nhóm và danh sách IP:cổng từng Worker. Bật bộ lọc OFFLINE để xem Worker mất kết nối. Reload và gửi bài toán không mở thêm Worker cục bộ.

## Giới hạn tài nguyên và chia việc

Mặc định mỗi Worker nhận một tác vụ; cả laptop dùng chung tối đa `max(1, số CPU logic - 1)` phép tính đồng thời. Có thể đặt `-HostParallelism 4` để giới hạn laptop đó ở 4, hoặc `-Count 20 -StartPort 5101` để thay số lượng/cổng. 50 Worker không tạo thêm 50 CPU.

Master tính tổng năng lực C theo từng laptop, không cộng lặp CPU của 50 Worker. Số phần việc tự động tối đa là `min(số dòng/cofactor, max(1, 2*C), 256)`. Master giữ chỗ ở cả cấp laptop và Worker; chọn laptop có tải/giới hạn thấp nhất rồi luân phiên Worker sẵn sàng.

Worker mới kết nối có thể nhận phần còn chờ. Không chuyển phần đang tính chỉ để ép mọi Worker tham gia. Bài toán 3 dòng có tối đa 3 phần việc; các Worker còn lại sẵn sàng là bình thường. UI hiển thị danh sách thực sự trả kết quả và số việc hoàn thành.

RESOURCE_AWARE tiếp tục dùng ngưỡng CPU/RAM/heap với hysteresis. `CPU_HIGH`, `RAM_HIGH`, `HEAP_HIGH` là tạm hoãn nhận việc, không phải lỗi kết nối. `HOST_CAPACITY` là laptop đã nhận đủ số tác vụ đồng thời. `PAUSED` cần bật lại ở UI. ETA dùng khối lượng hoàn thành, chưa có kết quả thì hiện **Đang ước lượng**.

Kết quả 3×3, 8×8, 100×100 được hiển thị đủ số ô. Giữ giới hạn 1.000.000 phần tử mỗi ma trận; kết quả trên 10.000 ô dùng khung xem trước được ghi rõ.

## Kết nối mạng và chẩn đoán

Master và Worker phải truy cập TCP được hai chiều. Nếu Windows Firewall chặn, tạo quy tắc nhận cổng 5000 trên A và đúng dải Worker đã chọn trên cả hai máy (5101–5150 trong ví dụ), giới hạn đúng IP máy đối tác và profile mạng đang dùng. Không tắt toàn bộ firewall. Hotspot phải cho phép hai thiết bị liên lạc; cùng tên Wi-Fi không bảo đảm điều này.

Trên A, sau khi các Worker đã đăng ký:

```powershell
.\scripts\diagnose-lan.ps1 -TestMatrix -MatrixSize 100 | Out-File -Encoding utf8 lan-diagnostics.json
```

Xem `matrixTestPassed=true`, `checkedCells=10000`, `workerIds`, trạng thái từng laptop và `jarMatchesMaster=true`. `UNKNOWN` có thể là tiến trình cũ chưa báo mã JAR hoặc chạy từ classes. Mã JAR phản ánh tiến trình thực tế; chép JAR mới mà không khởi động lại không cập nhật các tiến trình đang chạy.

`GET /api/status` và `/api/workers` vẫn dùng được. `POST /api/cluster/scale50` trả HTTP 410 vì không còn tự sinh Worker từ Web UI.

Ctrl+C trong terminal cụm Worker dừng toàn bộ cụm của laptop đó. Master giữ Worker OFFLINE sau ngưỡng heartbeat 9 giây, kiểm tra mỗi 2 giây. Master khởi động lại tạo phiên mới; Worker tự đăng ký lại, job cũ không được khôi phục.

## Nghiệm thu hai máy thật

1. A chạy Master và 50 Worker, B chưa mở: UI chỉ có nhóm A và đúng 50 Worker.
2. B mở thêm 50 Worker: hai terminal xác nhận REGISTERED; reload UI có đúng 100 ID khác nhau, chia A/B 50/50.
3. Chạy 3×3, 8×8, 100×100, đối chiếu kết quả; với bài toán đủ phần việc và hai máy sẵn sàng, log có Worker của cả hai laptop.
4. Kiểm tra số tác vụ đồng thời của mỗi laptop không vượt `HostParallelism`; sau reload/gửi job tổng Worker không tăng.
5. Dừng B khi còn phần việc: Master đưa phần bị mất lại hàng đợi, A tiếp tục nếu còn tài nguyên. Mở lại B và kiểm tra đăng ký lại.
6. Thử PAUSED, ngưỡng CPU/RAM, sai IP, cổng bận và JAR cũ; thông báo phải phân biệt được các trường hợp.

Kiểm thử localhost không chứng minh firewall/hotspot của laptop B hoặc mức tăng tốc LAN. Chỉ ghi nhận nghiệm thu hai laptop sau khi chạy checklist này trên mạng thật.
