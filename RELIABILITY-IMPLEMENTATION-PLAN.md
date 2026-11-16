# Kế hoạch hoàn thiện độ tin cậy và tính trung thực của kết quả

Ngày lập: 08/11/2026. Trạng thái: đã triển khai mã nguồn P0–P4; còn nghiệm thu trên hai laptop thật. P5 chưa triển khai.

## Trạng thái triển khai P0–P4

- **P0:** API legacy có `isLargeScale=true` trả HTTP 410; nhánh ngoại suy bị gỡ. `sampleGrid` trong response chỉ là preview của kết quả đã tính thật. API đồng bộ và async kiểm tra kích thước, dữ liệu hữu hạn và giới hạn request.
- **P1:** `MasterStateStore` dùng journal JSONL có `force(true)`, artifact input/result riêng với SHA-256, atomic replace, sửa torn tail, idempotency và khóa thư mục độc quyền. Attempt được ghi trước khi gửi. Không tuyên bố exactly-once execution.
- **P2:** Job async giữ ID, đầu vào, deadline tuyệt đối, kết quả commit và trạng thái qua restart; epoch là `masterSession` UUID mới. Có endpoint `/api/jobs`, `/api/jobs/result`, UI nhận lại Job sau reload, process supervisor cho Master. Dữ liệu vẫn chỉ nằm trên A.
- **P3:** protocol v4 bổ sung progress/cancel; dùng timeout ACK/network, idle progress, attempt hard limit và Job deadline riêng. Worker kiểm tra interrupt trong vòng lặp. Master giữ slot đến ACK `STOPPED`; Worker chưa dừng bị `QUARANTINED`.
- **P4:** launcher chạy nhiều nhóm JVM, chia CPU budget theo máy/nhóm, ID/cổng ổn định, restart riêng từng nhóm với backoff/giới hạn. Master yêu cầu supervisor restart group bị quarantine; lease file kèm PID/token ngăn launcher trùng và giúp tiến trình con tự dừng khi supervisor mất.
- **Chưa nghiệm thu:** chưa chạy kill/restart/LAN thực trên hai laptop; build không thay thế nghiệm thu mạng. Không dùng Docker.

## 1. Phạm vi và cam kết

- Giữ mô hình Java Master–Worker, TCP Socket, hai laptop, Worker ID và nhận diện laptop hiện có.
- Tác vụ được phép chạy lại; mỗi tác vụ logic chỉ có một kết quả được chấp nhận bền vững để tổng hợp. Không cam kết exactly-once execution.
- Khôi phục sau khi tiến trình Master chết là phạm vi bắt buộc. Chuyển Master sang laptop B khi cả A mất hoạt động là mốc riêng, không đánh đồng hai khả năng này.
- Một kết quả được báo hoàn tất phải là kết quả tính thật. Ước lượng chỉ dành cho ETA, không được dùng làm kết quả hoặc số đo benchmark.
- Không triển khai lại toàn bộ UI trong đợt này; chỉ bổ sung trạng thái và thao tác phục vụ phục hồi, hủy, timeout và chẩn đoán.

## 2. Hiện trạng đã đối chiếu

- `JobDispatcher`: queue, attempts và jobs nằm trong RAM; có retry và lọc kết quả theo attempt/session, chưa có thời hạn riêng cho attempt.
- `MasterServer`: asyncJobs nằm trong RAM; hủy Job được ghi thành FAILED; chưa có kho dữ liệu để khôi phục sau restart.
- `WorkerNode`: lưu các attempt đã nhận trong RAM, trả ACK và gửi lại kết quả khi thiếu ACK; heartbeat hoạt động độc lập với phép tính.
- `WorkerRuntime` và `WorkerClusterLauncher`: tất cả Worker logic trên một laptop dùng chung một JVM và ngân sách tính toán.
- `MessageType`: chưa có thông điệp tiến độ, hủy và xác nhận kết thúc attempt.
- `MasterHttpServer.handleLargeScaleCompute`: còn nhánh dựng kết quả/thời gian ước lượng. UI hiện dùng API async tính thật.
- Dữ liệu kết quả async trên 10.000 ô chỉ được giữ dưới dạng mẫu trong record hiện tại; lưu kết quả đầy đủ là điều kiện để phục hồi và tải lại chính xác.

## 3. Mốc P0 — Loại bỏ đường trả kết quả ước lượng

### Công việc

1. Gỡ `handleLargeScaleCompute` và helper chỉ phục vụ tính gần đúng/ngoại suy sau khi kiểm tra tham chiếu.
2. Yêu cầu legacy `POST /api/compute` có `isLargeScale=true` trả HTTP 410, mã lỗi ổn định và hướng dẫn chuyển sang `/api/compute/async`; không âm thầm thay đổi schema phản hồi thành 202.
3. Giữ API đồng bộ tính thật hiện có cho client phụ; áp dụng cùng kiểm tra kích thước, số hữu hạn và giới hạn dữ liệu với API async.
4. Cấm fallback từ lỗi Worker sang kết quả mẫu hoặc số giả định. Phân biệt rõ preview của kết quả đã tính với phép tính xấp xỉ.
5. Benchmark chỉ dùng phép tính thật; ghi rõ thời gian end-to-end gồm điều phối, truyền dữ liệu, tính và tổng hợp. Không chạy thêm phép tính tuần tự trong lúc đo phân tán.

### Nghiệm thu

- Yêu cầu legacy lớn bị từ chối rõ ràng; client không nhận success với giá trị ước lượng.
- 3×3, 8×8, 100×100 và kích thước lớn hơn ngưỡng preview được đối chiếu với baseline thực, gồm cả phép toán đơn ngôi.
- Không còn nhánh tính ngoại suy được gắn nhãn kết quả hoặc benchmark đo thực.

## 4. Mốc P1 — Mô hình trạng thái và lưu trữ bền vững

### Hợp đồng dữ liệu

- `jobId`, `taskId` ổn định qua restart; `attemptId` đổi mỗi lần giao lại.
- `masterEpoch` đổi khi Master nhận quyền điều phối mới; giữ kiểm tra workerSession và bổ sung processSession khi tách tiến trình.
- Job: QUEUED, RUNNING, RECOVERING, COMPLETED, FAILED, CANCELLED.
- Task logic: PENDING, RUNNING, COMMITTED, FAILED, CANCELLED.
- Attempt có trạng thái riêng: ASSIGNED, ACCEPTED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, EXPIRED, FAILED, CANCELLED.
- Đặc tả sơ đồ chuyển trạng thái và các bất biến trước khi nối vào giao thức.

### Kho lưu trữ

1. Thêm `JobStore` và `ResultStore` cục bộ với thao tác giao dịch/commit nguyên tử. Chốt công nghệ lưu trữ sau khi kiểm tra thư viện và môi trường Java của project.
2. Lưu phép toán, kích thước, dữ liệu nhập hoặc descriptor đủ để tái tạo chính xác, seed/phiên bản bộ sinh, kế hoạch chia việc, attempts, kết quả và các mốc thời gian.
3. Lưu dữ liệu ma trận lớn theo artifact/chunk, không nhồi ma trận vào mọi bản ghi sự kiện. Khối kết quả cần đủ thông tin vị trí, kích thước và checksum.
4. API chỉ trả 202 sau khi yêu cầu và đầu vào đã được lưu bền. Hỗ trợ khóa idempotency cho việc gửi lại yêu cầu khi client mất phản hồi; cùng khóa nhưng khác nội dung phải bị từ chối.
5. Ghi quyền sở hữu attempt bền vững trước khi gửi ASSIGN_TASK.
6. Lưu và xác minh artifact trước, sau đó commit tham chiếu cùng trạng thái Task. Chỉ gửi ACK xác nhận kết quả được lưu sau commit. Giao dịch có điều kiện bảo đảm một Task chỉ COMMITTED một lần.
7. Kết quả trùng đã commit nhận ACK phù hợp; kết quả cũ/khác epoch nhận phản hồi STALE riêng để Worker dừng gửi. Không coi STALE là đã nhận kết quả hợp lệ.
8. Nếu hết đĩa/lỗi ghi: không báo nhận thành công; dừng tiếp nhận hoặc báo lỗi lưu trữ. Dọn artifact mồ côi sau recovery, không xóa dữ liệu còn được tham chiếu.
9. Quy định dung lượng, thời gian giữ Job/kết quả, migration schema và định dạng version. Dữ liệu input không được sửa sau khi Job được nhận.

### Nghiệm thu

- Kill Master trước/sau các điểm commit/ACK: không mất Job đã nhận, không mất kết quả đã ACK, không ghép trùng.
- Mất ACK, gửi lại TaskResult, hai kết quả cạnh tranh, kết quả phiên cũ đều được xử lý đúng.
- Lỗi ghi đĩa, bản ghi/artifact chưa hoàn tất được phát hiện, không biến thành kết quả COMPLETED.

## 5. Mốc P2 — Khôi phục Master trên laptop A

1. Chỉ một Master được sở hữu thư mục trạng thái tại một thời điểm; khóa độc quyền khi khởi động.
2. Khi restart: đọc dữ liệu đã commit, tạo epoch mới, đưa attempts chưa kết thúc của epoch cũ về trạng thái hết hiệu lực; giữ nguyên các kết quả COMMITTED.
3. Tái tạo kế hoạch từ dữ liệu lưu, xếp hàng lại phần chưa hoàn thành. Job đã đủ kết quả nhưng chưa tổng hợp phải tổng hợp lại được mà không cần Worker tính lại.
4. Worker đăng ký lại, báo attempts và slot thực đang chiếm; hủy công việc thuộc epoch cũ. Không coi mọi slot đều rảnh chỉ vì Master vừa restart.
5. Lưu ngân sách thời gian/retry của Job; cấu hình rõ có tính thời gian Master ngừng hoạt động vào deadline hay không. Mặc định giữ deadline tuyệt đối và báo hết hạn rõ ràng, không tự gia hạn vô hạn.
6. Bổ sung API danh sách Job và đọc trạng thái/kết quả để reload UI tìm lại Job theo ID. UI thể hiện RECOVERING và sự thay đổi phiên Master.
7. Bổ sung chế độ giám sát tiến trình Master: restart có backoff và giới hạn số lần; phân biệt dừng chủ động với crash, không giành cổng của tiến trình khác.

### Nghiệm thu

- Kill tiến trình Master giữa Job; khởi động lại từ cùng thư mục dữ liệu; Job tiếp tục phần còn thiếu, kết quả đúng.
- Worker B còn sống tự đăng ký lại; kết quả epoch cũ không ghi đè trạng thái mới.
- Reload web thấy Job cũ và kết quả đã lưu. Hai Master dùng cùng thư mục dữ liệu bị chặn.
- Phạm vi này không bảo vệ khi ổ đĩa A hỏng hoặc cả laptop A không thể hoạt động.

## 6. Mốc P3 — Timeout, tiến độ và hủy từng attempt

1. Bổ sung thông điệp TASK_PROGRESS, CANCEL_TASK, CANCEL_ACK và phản hồi trạng thái attempt; nâng phiên bản giao thức, cập nhật đồng thời hai laptop.
2. Tách ba thời hạn: giao việc/ACK, không tiến triển và giới hạn chạy tuyệt đối; giữ Job deadline là lớp giới hạn tổng.
3. Dùng đồng hồ monotonic tại Master cho khoảng thời gian trong một phiên; không dựa vào đồng hồ hai laptop đồng bộ nhau.
4. Progress do chính vòng tính toán cập nhật theo đơn vị công việc. Heartbeat của luồng điều khiển không được tự coi là tiến độ tính toán. Chỉ thay đổi tiến độ hợp lệ mới gia hạn thời hạn không tiến triển.
5. Bổ sung điểm kiểm tra hủy và báo tiến độ trong vòng lặp nhân, khử Gauss/cofactor và thuật toán đồ thị; tốc độ gửi progress phải được giới hạn.
6. Hết hạn: đóng hiệu lực attempt cũ trước, gửi CANCEL_TASK; giao lại bằng attempt mới nếu còn ngân sách thử lại. Kết quả đến muộn bị loại.
7. CANCEL_ACK cần phân biệt đã nhận yêu cầu hủy với đã dừng phép tính. Chỉ thu hồi slot vật lý khi phép tính kết thúc hoặc tiến trình bị xác nhận dừng.
8. Worker không dừng sau khoảng chờ hủy phải được cách ly; sau P4 supervisor có thể khởi động lại nhóm tiến trình chứa nó. Không giải phóng giả slot hoặc liên tục giao lại vào tiến trình đang treo.
9. Hủy Job chuyển CANCELLED, lưu bền và hủy toàn bộ attempts; không tiếp tục retry Job đã hủy.
10. Các ngưỡng timeout cấu hình được theo loại tác vụ/khối lượng. Giá trị mặc định phải được hiệu chỉnh bằng đo thực; không dùng một timeout ngắn cho mọi kích thước.

### Nghiệm thu

- Worker vẫn heartbeat nhưng phép tính không tiến triển: attempt hết hạn, giao lại và hoàn thành ở Worker khác.
- Tác vụ chậm nhưng có tiến độ hợp lệ không bị coi là treo; vẫn chịu giới hạn tuyệt đối.
- Hủy rồi nhận kết quả muộn không chuyển Job thành COMPLETED.
- Không vượt ngân sách laptop khi attempt cũ chưa dừng; có test mô phỏng tác vụ không hợp tác với interrupt.

## 7. Mốc P4 — Cô lập và tự khởi động lại tiến trình Worker

### Kiến trúc đề xuất

- Giữ chế độ một JVM để tương thích và chạy gọn.
- Bổ sung chế độ nhiều nhóm JVM được supervisor quản lý. Ví dụ 50 Worker có thể chia 5 nhóm × 10 khi laptop có đủ ngân sách; không mặc định chạy 50 JVM.
- Chọn số nhóm không vượt ngân sách tính đồng thời của laptop. Phân bổ ngân sách nhóm sao cho tổng không vượt HostParallelism; metadata có machineId, groupId, processSession, groupParallelism.
- Master giữ chỗ cả cấp laptop, nhóm và Worker. Mỗi JVM có giới hạn nhóm riêng; không cấp nguyên HostParallelism cho mọi nhóm.
- ID và cổng Worker ổn định, chỉ process/worker session đổi khi restart. Khởi động nhiều nhóm phải có offset chỉ số Worker để không trùng ID.
- Supervisor lưu cấu hình/PID sở hữu, thu log từng nhóm, restart crash với backoff và giới hạn; không restart khi người dùng dừng có chủ đích.
- Nếu supervisor chết, cơ chế khởi động lại phải kiểm tra quyền sở hữu/process identity để không sinh tiến trình hoặc cổng trùng. Nếu cần tự hồi phục sau reboot, bổ sung chế độ dịch vụ riêng.
- UI phân biệt PAUSED, OFFLINE, RESTARTING, QUARANTINED; lệnh bật lại PAUSED không được ghi là restart JVM.

### Nghiệm thu

- Kill một nhóm JVM: chỉ Worker nhóm đó mất kết nối; các nhóm khác tiếp tục tính; nhóm lỗi đăng ký lại với session mới.
- Kill toàn bộ launcher ở chế độ cũ: tái phân phối sang laptop còn sống vẫn đúng.
- Tổng số tác vụ thực đang chạy không vượt giới hạn máy/nhóm khi restart hoặc khi Master phục hồi.
- Kiểm tra chi phí RAM trước khi chốt số nhóm trên máy đang dễ gặp RAM_HIGH.

## 8. Mốc P5 — Master dự phòng trên B (phạm vi nâng cao riêng)

Mục tiêu: tiếp tục phục vụ khi cả A không hoạt động, không chỉ khi tiến trình Java trên A chết.

1. Sao chép nhất quán trạng thái Job, input, kết quả và bản ghi commit sang B. Bản sao metadata không có artifact tương ứng không đủ để phục hồi.
2. Chọn rõ chính sách mất dữ liệu: sao chép bất đồng bộ có thể mất phần đã ACK sau điểm sao chép cuối; muốn không mất dữ liệu đã ACK khi A chết phải xác nhận sao chép bền sang B trước ACK, đổi lại không thể tiếp tục ACK khi B không truy cập được.
3. Với cấu hình chỉ hai laptop, đề xuất chuyển quyền thủ công có xác nhận Master cũ đã dừng hoặc bị cô lập; không tự chuyển quyền chỉ vì mất heartbeat giữa A/B.
4. Trước khi B tiếp quản phải vô hiệu hóa quyền điều phối của A; epoch đơn thuần không đủ nếu mỗi Master tự tăng epoch và vẫn tiếp xúc được một nhóm Worker.
5. Worker và UI hỗ trợ địa chỉ Master đang có quyền; Worker từ chối lệnh Master cũ. A quay lại phải đồng bộ và ở trạng thái dự phòng, không tự trở thành Master.
6. Nếu cần chuyển quyền tự động, thiết kế thêm cơ chế quorum/lease và một thành viên phân xử độc lập; đánh giá cả việc sao chép dữ liệu và ngăn Master cũ tiếp tục ghi. Không coi điện thoại phát Wi-Fi là thành viên phân xử khi chưa có dịch vụ đó.

### Nghiệm thu

- Tắt A, chuyển quyền hợp lệ sang B, tiếp tục Job từ trạng thái đã sao chép.
- Ngắt mạng giữa hai máy nhưng cả hai còn sống: không có hai Master cùng điều phối/commit.
- A quay lại không ghi đè dữ liệu B; tài liệu công bố rõ thời gian phục hồi và cửa sổ mất dữ liệu thực đo.

## 9. Thứ tự thực hiện và kiểm thử

Thứ tự: P0 → P1 → P2 → P3 → P4 → nghiệm thu hai laptop → P5 nếu cần dự phòng cấp laptop.

- P0 có thể hoàn thành độc lập, giảm ngay nguy cơ dùng sai số liệu.
- P1 là nền tảng của P2/P3; chốt mô hình trạng thái trước khi thêm thông điệp.
- Unit test: chuyển trạng thái, commit có điều kiện, retry/deadline, hết đĩa, checksum.
- Integration test: ACK mất, kết quả trùng/cũ, restart, cancel và treo có heartbeat.
- Process test: kill thật tiến trình Java từ test harness do bài test khởi tạo; không dừng ứng dụng người dùng đang chạy.
- LAN acceptance: hai laptop, cùng JAR/giao thức; đối chiếu toàn bộ kết quả, lưu log/task timeline và số liệu thời gian phục hồi.
- Benchmark sau khi bật lưu bền và các cơ chế mới; ghi chi phí độ tin cậy thay vì dùng số đo của phiên bản cũ.
- Giữ các test hiện có cho 3×3/8×8/100×100, 50+50 Worker, giới hạn tài nguyên và không tự tạo Worker khi gửi bài toán.

## 10. Đầu ra bàn giao

- Source, test, JAR đồng nhất, script supervisor/start/stop và cấu hình timeout/store.
- Hướng dẫn nâng cấp giao thức; không trộn JAR cũ/mới trong cùng lượt triển khai.
- Hướng dẫn backup/restore và giới hạn phục hồi của từng mốc.
- Báo cáo sự cố có thời điểm phát hiện, requeue, nhận lại, hoàn thành và kiểm tra kết quả.
- API/README/UI thống nhất cách diễn đạt: tác vụ có thể chạy lại, kết quả logic chỉ commit một lần; không tuyên bố exactly-once execution.
