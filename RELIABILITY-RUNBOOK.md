# Runbook độ tin cậy P0–P4

Tài liệu này mô tả cách chạy bản Java hiện tại trên Windows bằng PowerShell và JAR, không cần Docker. Cả hai laptop phải dùng cùng JAR giao thức 4.

## Các bảo đảm và giới hạn

- Kết quả chỉ được báo `COMPLETED` sau khi các lát cắt tính thật đã được lưu và tổng hợp. Nhánh legacy `/api/compute` với `isLargeScale=true` trả HTTP 410; dùng `/api/compute/async`.
- Một task có thể được thực thi lại sau lỗi hoặc restart. Mỗi `taskId` chỉ được commit một kết quả thành công; hệ thống **không bảo đảm mỗi task chỉ chạy một lần**.
- Job async, đầu vào, kết quả từng task và trạng thái cuối được lưu trên đĩa A. Master giữ khóa độc quyền thư mục dữ liệu. Khởi động lại cùng cấu hình sẽ phục hồi task chưa commit; deadline gốc vẫn được giữ.
- Kết quả và đầu vào lớn nằm trong `data/master-<port>/artifacts/`; journal `master-state.jsonl` lưu metadata/checksum. Không sửa hoặc xóa file khi Master chạy. Dung lượng lưu tăng theo số Job; hiện chưa có chính sách tự xóa lịch sử.
- Khôi phục này chịu được tiến trình Master chết khi thư mục dữ liệu trên A còn nguyên. Nó không tạo Master dự phòng, không bảo vệ khi ổ đĩa hoặc cả laptop A hỏng.
- Timeout không tiến triển mặc định 60 giây, giới hạn tuyệt đối của một attempt 180 giây, Job mặc định 300 giây. Có thể cấu hình khi chạy Master. Worker chưa dừng sau lệnh hủy được đánh dấu `QUARANTINED`; sau 10 giây, Master yêu cầu supervisor khởi động lại process group nếu Worker được quản lý. Slot chỉ được trả khi Worker xác nhận đã dừng hoặc được phát hiện OFFLINE.
- Worker được quản lý theo nhóm JVM. Mặc định 50 Worker nằm trong một nhóm; dùng `-Groups 2` trở lên để cô lập lỗi theo nhóm. Ngân sách tính toán được chia giữa các nhóm và không cộng vượt `HostParallelism`.

## Đóng gói và khởi chạy hai laptop

Trên A, trong thư mục project:

```powershell
mvn '-DskipTests' package
```

Chép cùng file `target/distributed-task-scheduling-1.0.0.jar` và thư mục `scripts/` sang A và B. So sánh SHA-256 mà script in ra. Dừng phiên bản cũ trước khi đổi JAR; JAR giao thức cũ bị từ chối.

Trên A, lấy IP Wi-Fi bằng `ipconfig`, sau đó mở hai terminal:

```powershell
.\scripts\start-lan-master.ps1 -Port 5000
```

```powershell
.\scripts\start-lan-workers.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.10 -MachineId laptop-a -StartPort 5101 -Count 50 -Groups 1
```

Trên B, thay địa chỉ Master bằng IP thật của A và WorkerAddress bằng IP Wi-Fi của B:

```powershell
.\scripts\start-lan-workers.ps1 -MasterAddress 192.168.1.10 -WorkerAddress 192.168.1.20 -MachineId laptop-b -StartPort 5101 -Count 50 -Groups 2
```

Terminal Worker hiển thị `REGISTERED` sau ACK của Master. Mở UI trên A bằng `scripts/start-ui.ps1`; reload lấy lại cụm và Job từ API của Master. Master dùng `data/master-5000` mặc định. Giữ thư mục đó để phục hồi Job.

## Kiểm tra và vận hành

- `/api/jobs` liệt kê Job sau reload. `/api/jobs/progress?jobId=...` trả tiến độ/trạng thái. `/api/jobs/result` đọc kết quả ma trận theo trang, tối đa 20.000 ô mỗi lần.
- `Idempotency-Key` có thể gửi cùng request khi client không biết request trước đã được Master nhận chưa. Dùng lại khóa với nội dung khác sẽ bị từ chối.
- `/api/cluster` là snapshot Worker hiện đăng ký, gồm laptop, process group, `QUARANTINED`, `OFFLINE`, `PAUSED` và nguyên nhân chưa nhận việc. Dashboard không tự tạo Worker.
- `RAM_HIGH`, `CPU_HIGH`, `HEAP_HIGH` là ngưỡng hoãn nhận việc ở cấp laptop; 50 Worker cùng một laptop có thể cùng hiện trạng thái đó vì dùng chung một máy. Nếu phần cứng vẫn còn ngân sách phù hợp, đổi `-RamHigh`/`-RamLow` hoặc chọn `-Policy ROUND_ROBIN`; số Worker kết nối không làm tăng ngân sách CPU/RAM.
- Nếu Master chết bất thường, supervisor thử khởi động lại tối đa 5 lần với backoff. Nếu cả supervisor chết, Master con theo dõi lease và tự dừng; khi chạy lại dùng cùng `-DataDirectory`.
- Nếu một nhóm Worker JVM chết, supervisor chỉ khởi động lại nhóm đó. Nếu supervisor bị dừng cưỡng bức, các nhóm con kiểm tra lease/token và tự dừng để tránh trở thành tiến trình mồ côi. Khởi động lại launcher sau khi cổng cũ được giải phóng.
- Sao lưu khi Master đã dừng: chép toàn bộ `data/master-5000/`, gồm journal, lock metadata và `artifacts/`. Chỉ restore nguyên thư mục vào cùng đường dẫn dữ liệu rồi khởi động Master.

## Benchmark và nghiệm thu

Không dùng kết quả ước lượng hoặc preview làm kết quả tính. Benchmark chỉ dùng phép tính thật, ghi thời gian end-to-end và cấu hình JAR, laptop, số nhóm, `HostParallelism`, số task, kích thước ma trận và trạng thái cụm. Không chạy phép tính tuần tự trong khoảng thời gian đo phân tán. Nghiệm thu restart, treo task, mất mạng, giới hạn tài nguyên và kết quả 3×3/8×8/100×100 trên hai laptop thật; build localhost không thay thế nghiệm thu LAN.

P5 — Master dự phòng trên B, quorum/lease giữa hai máy và phục hồi khi mất toàn bộ A — nằm ngoài phạm vi P0–P4.
