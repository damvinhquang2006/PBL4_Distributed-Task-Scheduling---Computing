# Chạy cụm nhiều laptop qua Radmin VPN

Hướng dẫn này dùng mô hình **Master luôn ở máy A**, Worker chạy trên A và B, một giao diện web do A phục vụ cho người dùng của cả hai máy. Hệ thống gộp Worker theo ID duy nhất vào một cụm chung; người dùng có thể đổi tên hiển thị và tạm dừng/bật lại từng Worker online.

## 1. Nối hai máy bằng Radmin VPN

1. Trên A tạo mạng Radmin VPN riêng và đặt mật khẩu.
2. Trên B tham gia đúng mạng đó.
3. Xác nhận hai máy xuất hiện trong danh sách thành viên và dùng chức năng Ping của Radmin để kiểm tra A↔B.
4. Trên mỗi máy chạy `ipconfig` và ghi lại IPv4 của card **Radmin VPN**. Không dùng `127.0.0.1`, địa chỉ Wi-Fi, VMware hoặc địa chỉ của card VPN khác.

Tham khảo [hướng dẫn chính thức của Radmin VPN](https://www.radmin-vpn.com/help/). Địa chỉ Radmin của mỗi máy có thể thay đổi theo mạng; các lệnh phía dưới dùng `<IP_A_RADMIN>` và `<IP_B_RADMIN>` làm chỗ thay thế.

## 2. Chuẩn bị cùng phiên bản chương trình

Trên A, tại thư mục dự án:

```powershell
mvn clean package
Get-FileHash -Algorithm SHA256 .\target\distributed-task-scheduling-1.0.0.jar
```

Trên Windows, dừng Master/Worker cũ chạy từ JAR trong `target` bằng `Ctrl+C` trước khi build; nếu không, JVM có thể khóa JAR khiến Maven `clean` thất bại.

Chép sang B cùng JAR và thư mục `scripts` (giữ cấu trúc thư mục như trên A). Đối chiếu SHA256 để chắc chắn hai máy dùng đúng cùng bản. B cần Java 21 trở lên; không cần Maven nếu chỉ chạy JAR đã chép.

## 3. Mở các cổng cần thiết

Các cổng mặc định:

| Máy | Cổng vào | Dùng cho |
|---|---:|---|
| A | TCP 5000 | Master nhận Worker, heartbeat và kết quả. |
| A | TCP 8080 | REST API được hai giao diện trình duyệt dùng chung. |
| A | TCP 5173 | Web UI được phục vụ tới trình duyệt A và B. |
| A và B | TCP 5101–5103 | Ba Worker ví dụ trên mỗi máy; Master gọi ngược tới Worker. |

Cho phép các kết nối đến từ IP Radmin của máy đối tác trên Windows Firewall. Ví dụ, mở PowerShell với quyền phù hợp trên A (thay IP thật):

```powershell
New-NetFirewallRule -DisplayName 'PBL4 Master va Web UI qua Radmin' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000,8080,5173 -RemoteAddress '<IP_B_RADMIN>' -Profile Any
```

Trên B, cho phép Master A gọi ngược đến các Worker:

```powershell
New-NetFirewallRule -DisplayName 'PBL4 Workers qua Radmin' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5101-5103 -RemoteAddress '<IP_A_RADMIN>' -Profile Any
```

Nếu chạy nhiều hơn ba Worker, mở đúng dải `StartPort` đến `StartPort + Count - 1`. Trên A, các cổng Worker A cũng phải nhận kết nối kiểm tra và giao việc từ Master trên cùng máy. Không tắt toàn bộ firewall; không mở các cổng này ra Internet công cộng.

Kiểm tra đường TCP trước khi chạy demo:

```powershell
# Từ B tới Master A
Test-NetConnection '<IP_A_RADMIN>' -Port 5000
Test-NetConnection '<IP_A_RADMIN>' -Port 8080
Test-NetConnection '<IP_A_RADMIN>' -Port 5173

# Từ A tới Worker đầu tiên trên B (sau khi Worker B đã chạy)
Test-NetConnection '<IP_B_RADMIN>' -Port 5101
```

`TcpTestSucceeded : True` chỉ xác nhận cổng nhận kết nối; điều kiện Worker kết nối hoàn chỉnh là log có `REGISTERED` và Worker xuất hiện ở giao diện.

## 4. Khởi động cụm

Mở các terminal riêng, từ thư mục dự án trên mỗi laptop.

**A — Master** (chạy thường xuyên trong suốt demo):

```powershell
.\scripts\start-lan-master.ps1 -Port 5000 -BindAddress 0.0.0.0 -Policy ROUND_ROBIN
```

*(Mặc định có thể dùng `RESOURCE_AWARE`, nhưng nếu máy demo đang sử dụng > 90% RAM, nên dùng `-Policy ROUND_ROBIN` hoặc `LEAST_LOADED` để tránh chặn cấp phát tác vụ do `RAM_HIGH`).*

**A — ba Worker của máy A:**

```powershell
.\scripts\start-lan-workers.ps1 -MasterAddress '<IP_A_RADMIN>' -WorkerAddress '<IP_A_RADMIN>' -MachineId demo-a -MachineName 'Laptop A' -Count 3 -StartPort 5101
```

**B — ba Worker của máy B:**

```powershell
.\scripts\start-lan-workers.ps1 -MasterAddress '<IP_A_RADMIN>' -WorkerAddress '<IP_B_RADMIN>' -MachineId demo-b -MachineName 'Laptop B' -Count 3 -StartPort 5101
```

*(Tùy chọn: thêm `-StartPaused` nếu muốn Worker máy B đăng ký sẵn ở trạng thái **Tạm dừng / Warm Standby** để chủ động bấm bật từ Web UI khi demo).*

WorkerAddress phải là IP đang có trên chính laptop chạy Worker. `MachineId` A/B phải khác nhau; script tự tạo các `workerId` khác nhau từ MachineId. Tăng `Count` nếu muốn nhiều Worker logic, nhưng nhiều Worker trên một laptop **không tạo thêm CPU hoặc RAM**. `HostParallelism` là giới hạn tính toán chung của laptop.

Đợi thông báo `REGISTERED 3/3` ở cả hai máy. Nếu chỉ thấy `opened` hoặc `BIND`, Worker chưa được Master xác nhận.

## 5. Mở giao diện trên cả hai máy

Trên A, chạy thêm terminal:

```powershell
.\scripts\start-ui.ps1 -HostAddress 0.0.0.0 -Port 5173
```

Trên trình duyệt của cả A và B, mở cùng URL:

```text
http://<IP_A_RADMIN>:5173
```

Không mở `localhost:5173` trên B: `localhost` của B trỏ về chính B. Giao diện tự dùng hostname/IP trên URL để gọi API của Master A ở cổng `8080`. Hai người dùng thấy cùng snapshot, danh sách job và trạng thái Worker; cập nhật xuất hiện theo chu kỳ làm mới giao diện.

Trong giao diện Web (gồm 4 tab: **1. Giải bài toán**, **2. Quản lý Master–Worker**, **3. Benchmark (1 vs N)**, **4. Job, Kết quả & Failover**):

- Ở tab **Quản lý Master–Worker**:
  - Tổng Worker bằng tổng số đã đăng ký từ A và B; mỗi laptop có nhóm riêng theo `MachineId`.
  - Bấm biểu tượng bút chì để đổi tên hiển thị (`displayName`). Tên mới được lưu bền vững và đồng bộ trên cả 4 tab mà không làm gián đoạn kết nối hay mất `workerId`.
  - Bấm **Tạm dừng** / **Bật lại** trên từng Worker `Online`, hoặc dùng thanh điều phối hàng loạt (**Bật tất cả Worker đang tạm dừng**, **Giữ đã chọn & Tạm dừng còn lại**).
  - Có thể khởi động/dừng thêm Worker cục bộ trên máy Master A ngay từ giao diện (mặc định dùng cổng từ `5201`).
  - Có thể đổi chính sách lập lịch, gửi job (lên tới `50.000.000` phần tử) và theo dõi cùng trạng thái Master từ cả hai trình duyệt.

## 6. Chẩn đoán và tiêu chí nghiệm thu

Trên A, lấy snapshot và kiểm tra cổng/Worker:

```powershell
.\scripts\diagnose-lan.ps1 -MasterAddress '<IP_A_RADMIN>' -MasterPort 5000 -HttpPort 8080
```

Để gửi phép nhân kiểm tra và xác nhận kết quả:

```powershell
.\scripts\diagnose-lan.ps1 -MasterAddress '<IP_A_RADMIN>' -MasterPort 5000 -HttpPort 8080 -TestMatrix -MatrixSize 8
```

Demo nhiều máy được nghiệm thu khi đồng thời đạt các điểm sau:

1. A và B thấy nhau trong Radmin và kiểm tra TCP hai chiều thành công.
2. Master A báo đủ đăng ký; snapshot có cả `demo-a` và `demo-b`, tổng Worker bằng tổng Worker đăng ký ở hai máy.
3. Cả hai trình duyệt truy cập được cùng UI/API bằng IP Radmin A.
4. Đổi tên một Worker từ A hoặc B và thấy tên mới trên cả hai giao diện, ID không đổi.
5. Tạm dừng một Worker trên B từ giao diện A; Worker vẫn online nhưng bị tạm dừng. Bật lại từ giao diện B và thấy A cập nhật.
6. Job kiểm tra trả kết quả đúng; snapshot hoặc tiến độ cho thấy Worker từ A và B đã hoàn thành tác vụ.

Nếu kết nối lỗi: kiểm tra Radmin ping trước, sau đó IP adapter, TCP, Windows Firewall, `MasterAddress`, `WorkerAddress`, cùng JAR và `MachineId` duy nhất. Nếu API hoạt động nhưng UI báo mất Master, xác nhận đã mở UI bằng IP Radmin A (không phải localhost trên B), Master API nghe ở `8080` và script UI đang dùng `--host 0.0.0.0`.

Ứng dụng hiện không có tài khoản hay TLS. Chỉ chia sẻ mạng Radmin với người tin cậy và giới hạn firewall theo đúng IP máy demo. Trạng thái kiểm thử thực tế nằm trong [TESTING.md](TESTING.md); mô phỏng hai địa chỉ trên một máy không thay thế nghiệm thu vật lý A↔B.
