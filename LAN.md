# Chạy tính toán ma trận trên hai laptop Windows qua LAN

## Mô hình

- Laptop A: Master TCP 5000, Worker `laptop-a-worker` TCP 5001.
- Laptop B: Worker `laptop-b-worker` TCP 5001.
- Cả hai Worker đăng ký vào Master trên A. Master gửi tác vụ trực tiếp đến từng Worker; kết nối phải thông cả hai chiều.
- Chỉ dùng mạng nội bộ tin cậy. Giao thức hiện tại không có xác thực/TLS; không mở cổng ra Internet.
- Cùng Java 21+ và cùng JAR giao thức v2 trên cả hai máy. Không trộn với JAR cũ.

Nếu dùng gói **PBL4-LAN-release.zip**, giải nén trên cả hai máy; gói đã có JAR trong `target/`, script trong `scripts/` và báo cáo kiểm thử. Không cần chạy Maven lại để demo.

## 1. Chuẩn bị

Trong thư mục project trên A:

```powershell
mvn package
Get-FileHash -Algorithm SHA256 -LiteralPath '.\target\distributed-task-scheduling-1.0.0.jar'
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' }
```

Trên B, cài Java 21+ (`java -version`). Chép thư mục `scripts`, tài liệu này và JAR từ A sang B, giữ cấu trúc `scripts/` và `target/` cạnh nhau; B không cần Maven hoặc mã nguồn. Đối chiếu SHA256 của hai JAR.

Lấy IPv4 của card Wi-Fi/Ethernet đang dùng, không lấy IP VPN hoặc loopback. Trong ví dụ bên dưới **192.168.1.10 là A, 192.168.1.20 là B**; thay bằng IP thật. Dùng địa chỉ ổn định trong thời gian demo. Nếu IP thay đổi, chạy lại Worker với IP mới.

## 2. Mở ba terminal

**Terminal 1 trên A — Master:**

```powershell
.\scripts\start-lan-master.ps1
```

**Terminal 2 trên A — Worker cục bộ:**

```powershell
.\scripts\start-lan-worker.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.10 -WorkerId laptop-a-worker -Capacity 2
```

**Terminal trên B — Worker từ xa:**

```powershell
.\scripts\start-lan-worker.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.20 -WorkerId laptop-b-worker -Capacity 4
```

Capacity trong ví dụ chỉ là giới hạn tác vụ đồng thời, không khẳng định B nhanh gấp đôi A. Bỏ tham số để dùng mặc định `max(1, số bộ xử lý khả dụng - 1)`. Mỗi máy chỉ cần một Worker; dùng nhiều Worker trên cùng máy không tạo thêm CPU/RAM.

`-WorkerAddress` là địa chỉ Master phải gọi được; không đặt `127.0.0.1` cho Worker trên B. `-BindAddress` mặc định `0.0.0.0` (lắng nghe các card mạng), không phải địa chỉ để quảng bá.

Script chạy Java ngay trong terminal hiện tại. Ctrl+C dừng tiến trình của terminal đó. Các tham số `-MasterPort`, `-WorkerPort`, `-Port` và `-JarPath` cho phép dùng cổng/đường dẫn khác. Các lệnh localhost cũ vẫn chạy; cấu hình LAN bật `RESOURCE_AWARE` qua script mới.

## 3. Kiểm tra TCP và Windows Firewall

Sau khi các tiến trình đã mở cổng, chạy từ **B đến Master A**:

```powershell
.\scripts\test-lan-connection.ps1 -RemoteAddress 192.168.1.10 -Port 5000
```

Chạy từ **A đến Worker B**:

```powershell
.\scripts\test-lan-connection.ps1 -RemoteAddress 192.168.1.20 -Port 5001
```

Master tự kiểm tra kết nối ngược khi đăng ký. Ping ICMP không thay thế kiểm tra TCP. Nếu đăng ký bị từ chối, xem log `Worker reconnect`.

Nếu cần mở firewall, tự chạy các lệnh phù hợp trong PowerShell Administrator. Ví dụ cho mạng có profile **Private**, giới hạn đúng máy đối tác (thay IP thật trước khi chạy):

```powershell
# Trên A: nhận đăng ký/heartbeat/kết quả từ B và Worker A
New-NetFirewallRule -DisplayName 'PBL4 LAN Master 5000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000 -RemoteAddress 192.168.1.20,192.168.1.10 -Profile Private
# Trên A: Worker A nhận việc từ Master A
New-NetFirewallRule -DisplayName 'PBL4 LAN Worker A 5001' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5001 -RemoteAddress 192.168.1.10 -Profile Private
# Trên B: Worker B nhận việc từ Master A
New-NetFirewallRule -DisplayName 'PBL4 LAN Worker B 5001' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5001 -RemoteAddress 192.168.1.10 -Profile Private
```

Script không tự thay đổi firewall. Kiểm tra profile bằng `Get-NetConnectionProfile`; nếu mạng đang là Public thì quy tắc Private không áp dụng. Không tắt toàn bộ firewall. Wi-Fi khách có thể bật client isolation khiến hai laptop không truy cập nhau dù cùng SSID; cần mạng cho phép các thiết bị liên lạc.

## 4. Quan sát và nhập bài toán

Trong menu Master, chọn xem Worker để thấy ID, IP:cổng, trạng thái, capacity, tác vụ, CPU/RAM/heap và `admission`:

| Giá trị | Ý nghĩa |
|---|---|
| READY | Có thể nhận thêm việc |
| CAPACITY | Đã đạt số tác vụ đồng thời |
| CPU_HIGH / RAM_HIGH / HEAP_HIGH | Tạm dừng nhận mới vì tải cao |
| PAUSED | Người dùng chủ động tạm dừng |
| OFFLINE | Mất heartbeat |
| RETRY_BACKOFF | Tạm chờ sau khi giao việc thất bại/từ chối |
| UNKNOWN trong metrics | Không có số đo hợp lệ, không giả định là 0% |

Chọn phép toán ma trận như trước: `+`, `-`, `x`, `%`, `det`, `rank`. Mỗi job được chia tối đa `min(số dòng hoặc số hạng, 4 × tổng capacity, 64)` tác vụ. Máy còn chỗ nhận thêm tác vụ từ hàng đợi, bao gồm máy mới gia nhập. Ma trận quá nhỏ có thể không đủ phần việc cho cả hai máy.

CPU/RAM là số đo toàn máy, heap là bộ nhớ của JVM Worker. Lấy mẫu mỗi giây, trung bình tối đa ba mẫu và gửi mỗi ba giây. `RESOURCE_AWARE` ngừng giao mới tại CPU 85%, RAM 90%, heap 85%; nhận lại khi xuống dưới 75%, 80%, 75%. Tác vụ đang chạy tiếp tục. Chọn Worker theo tải/capacity, rồi CPU, RAM, ID. Nếu tất cả quá tải, job chờ đến khi có tài nguyên hoặc hết timeout.

Ví dụ tùy chỉnh:

```powershell
.\scripts\start-lan-master.ps1 -JobTimeoutSeconds 600 -CpuHigh 90 -CpuLow 80 -RamHigh 90 -RamLow 80 -HeapHigh 85 -HeapLow 75
```

`-Policy ROUND_ROBIN` hoặc `-Policy LEAST_LOADED` bỏ điều kiện ngưỡng tài nguyên nhưng vẫn giới hạn capacity. Menu đổi chính sách lần lượt qua ba lựa chọn. `-DemoDelay` trên Worker bật lại độ trễ hiển thị; mặc định tắt để tránh làm sai số đo.

## 5. Phục hồi và giới hạn

- Worker tự đăng ký lại với backoff 1, 2, 4, 8, tối đa 10 giây khi mất Master hoặc Master đổi phiên.
- Master phát hiện mất heartbeat sau ngưỡng 9 giây, kiểm tra mỗi 2 giây; không phải chính xác ở giây thứ 9.
- Khi Worker mất kết nối, các tác vụ của lần thực thi đó được đưa lại vào hàng đợi, tối đa ba lần thực thi/giao việc không rõ kết quả. Từ chối vì bận chưa tính là một lần thực thi; timeout job vẫn giới hạn thời gian chờ.
- Worker ACK nhận tác vụ trước khi tính. Gửi lại cùng attempt ID không chạy lại cùng tác vụ trên Worker. Khi giao lại sang Worker khác có thể có hai phép tính thực tế; Master chỉ chấp nhận kết quả của lần hiện hành, không cam kết exactly-once execution.
- Kết quả chưa ACK được gửi lại tối đa 300 giây. Kết quả trùng/cũ được ACK và bỏ qua. Dữ liệu kết quả được giải phóng sau ACK, khóa chống trùng giữ tối đa 300 giây sau khi hoàn thành.
- Tạm dừng từ menu không tắt heartbeat và không hủy tác vụ đang chạy; khi bật lại, Worker nhận thêm việc.
- Master chỉ báo COMPLETED sau khi mọi phần thành công và tổng hợp thành công. Lỗi tính toán kết thúc job; không trả ma trận thiếu phần như kết quả hợp lệ.
- Master khởi động lại không phục hồi job cũ; nhập lại bài toán. Job timeout giải phóng trạng thái Master, nhưng tác vụ đã gửi có thể vẫn tính xong trên Worker; kết quả muộn sẽ bị bỏ qua.
- Truyền ma trận B/ma trận định thức theo từng tác vụ còn tốn băng thông và bộ nhớ. Không bảo đảm hai máy nhanh hơn một máy. CPU/RAM chỉ là tín hiệu điều phối, không phải dự báo chính xác bộ nhớ của tác vụ kế tiếp.

## 6. Nghiệm thu hai laptop

1. Đối chiếu JAR, Java, IP và kiểm tra TCP hai chiều.
2. Master thấy cả `laptop-a-worker` và `laptop-b-worker`; chạy ma trận đủ số dòng. Log `[Result] ... <- worker-id` phải có cả hai ID; đối chiếu kết quả tuần tự trên console.
3. Thử cộng/trừ/modulo, nhân ma trận chữ nhật, định thức và hạng của ma trận suy biến; thử số dòng không chia hết cho số phần.
4. Trong một job đủ dài, dừng Worker B. A phải nhận việc được giao lại và trả kết quả đúng trong timeout. Khởi động lại B; B tự gia nhập. Dùng `-DemoDelay` chỉ cho kịch bản sự cố nếu bài toán kết thúc quá nhanh.
5. Dừng cả hai Worker rồi khởi động lại trước timeout: job chờ và tiếp tục. Để quá timeout: job thất bại rõ ràng.
6. Tạm dừng B bằng menu: B vẫn ONLINE nhưng PAUSED, heartbeat không tự bật lại. Bật lại để nhận việc.
7. Khởi động Worker trước Master, sau đó mở Master. Khởi động lại Master và gửi job mới; Worker tự đăng ký lại.
8. Đo với chỉ A, chỉ B, rồi A+B bằng `LanBenchmarkApp` bên dưới; dùng cùng kích thước/seed, tắt demo delay, giữ số lần warmup và đo giống nhau.

```powershell
# Chạy thay MasterApp trên A; trước đó dừng Master console để tránh trùng cổng.
# Mỗi cấu hình dùng đúng tập Worker tương ứng; các Worker tự nối lại Master mới.
java -cp .\target\distributed-task-scheduling-1.0.0.jar com.pbl4.app.LanBenchmarkApp --port 5000 --workers 2 --size 300 --seed 42 --rounds 3
```

Ứng dụng benchmark đợi đúng số Worker, dùng cùng dữ liệu cố định, một lượt warmup và in thời gian tuần tự/phân tán, speedup cùng kiểm tra sai số. Cho mỗi cấu hình, đặt `--workers 1` hoặc `2` tương ứng. Không dùng `-DemoDelay` khi đo. Nếu CPU/RAM khiến job chờ, thời gian chờ được tính vào kết quả phân tán.

`mvn test` kiểm tra logic và TCP localhost, không chứng minh firewall/Wi-Fi của laptop B. Chỉ điền số đo LAN sau khi chạy thật; không dùng số đo localhost làm bằng chứng tăng tốc hai laptop.
