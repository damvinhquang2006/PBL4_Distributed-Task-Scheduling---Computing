# Xác minh cụm nhiều Worker — 27/10/2026

## Kiểm thử tự động

- Maven package thành công: **57 kiểm thử, 0 thất bại, 0 lỗi, 0 bỏ qua**.
- Chạy 50 Worker trên `127.0.0.1` và 50 Worker trên `127.0.0.2`, cùng dải cổng. Đăng ký đủ 100 ID riêng, nhóm A/B 50/50, đọc lại snapshot HTTP vẫn đúng.
- Với giới hạn A=2 và B=3, tổng năng lực C=5, bài toán 100×100 được chia thành 10 tác vụ. Kiểm tra đủ 10.000 ô của A×I và xác nhận cả hai nhóm có kết quả; không tạo thêm Worker.
- 100 yêu cầu giữ chỗ đồng thời chỉ được cấp tổng cộng 5 chỗ, đúng giới hạn 2/3 của hai laptop. Worker runtime cũng từ chối vượt giới hạn chung dù yêu cầu đến các cổng Worker khác nhau.
- Kiểm tra đăng ký trước Master, Master khởi động lại, laptop B gia nhập khi A đang bận, lỗi kết nối ngược, ID trùng, mất Worker, PAUSED, ngưỡng tài nguyên và chống kết quả trùng/cũ.
- Giao thức 2 bị từ chối với hướng dẫn cập nhật JAR. Endpoint scale50 trả 410 và số Worker không tăng.
- Tiến độ của hai lát cắt có khối lượng 1 và 3 đạt 25% sau lát cắt nhỏ, thay vì 50%. Snapshot dùng thông tin laptop ONLINE sau khi đổi cấu hình, không lấy giới hạn cũ từ Worker OFFLINE.
- Giữ kiểm tra chính xác cho phép toán ma trận/đồ thị cũ, gồm 3×3, 8×8, 100×100 và toàn bộ các phép toán web hiện có.

## Kiểm tra bản JAR và giao diện

- Master và hai launcher Worker chạy thành **ba tiến trình Java riêng**, mỗi launcher mở 50 Worker và dùng ngân sách tính toán chung.
- Lần mở dải mặc định phát hiện cổng 5040 bận; launcher báo đúng `opened 49/50`, `REGISTERED 49/50`, UI hiển thị đúng 98 Worker. Không dừng ứng dụng đang giữ cổng này.
- Chuyển cụm kiểm thử sang 5101–5150: đủ 100 Worker, A/B 50/50. Reload UI vẫn có đúng 100 thẻ Worker, đúng hai laptop; không có nút tự tạo thêm 50 Worker.
- Đã chạy ma trận 100×100 từ UI: DOM chứa đúng 10.000 ô số và kết quả nêu Worker thuộc cả `laptop-a` và `laptop-b`.
- Chẩn đoán A×I xác minh đủ 10.000 ô, `matrixTestPassed=true`, cả 100 Worker báo JAR khớp Master. Với C=5, 10 Worker nhận 10 phần việc; các Worker còn lại vẫn được hiển thị sẵn sàng.
- Dừng launcher B: sau ngưỡng heartbeat, API có 50 kết nối, 50 OFFLINE. UI mặc định hiện 50 thẻ A; bật bộ lọc OFFLINE mới hiện cả 100. A tiếp tục hoàn thành A×I 100×100 với 4 tác vụ, chỉ Worker A có kết quả.
- Mở lại B: UI tự cập nhật về 100 Worker, A/B 50/50. Các tiến trình và tab kiểm thử đã được dừng/đóng sau khi xác minh.
- `npm run build` thành công. Các script PowerShell được kiểm tra bằng Parser; script chẩn đoán đã chạy thực tế cả lúc đủ 100 Worker và khi B OFFLINE. Giới hạn thời gian probe TCP giúp chẩn đoán không chờ tuần tự quá lâu khi nhiều Worker mất kết nối.

Ảnh giao diện: `target/cluster-ui-laptop-a.jpg`, `target/cluster-ui-laptop-b.jpg`, `target/cluster-ui-result.jpg`. Báo cáo chẩn đoán: `target/cluster-100-worker-diagnostic.json`, `target/cluster-after-b-offline.json`.

## Lệnh tái kiểm tra và giới hạn xác minh

Windows tạm khóa một số tệp build cũ, nên kiểm thử dùng thư mục riêng. Java 25 trên môi trường này cần chuyển socket đánh thức nội bộ của bộ kiểm thử sang TCP:

```powershell
mvn -q '-Dpbl4.buildDirectory=target/cluster-validation' '-DargLine=-Djdk.net.unixdomain.tmpdir=target/unused-unix-sockets' package
Copy-Item .\target\cluster-validation\distributed-task-scheduling-1.0.0.jar .\target\distributed-task-scheduling-1.0.0.jar -Force
```

Báo cáo kiểm thử ở `target/cluster-validation/surefire-reports/`. Tham số thư mục build là tùy chọn; mặc định dự án vẫn dùng `target/`.

Hai nhóm laptop ở trên được **mô phỏng trên một máy**, không phải nghiệm thu hai laptop qua Wi-Fi/4G thực tế. Chưa đo tốc độ tăng tốc LAN, chưa thay đổi firewall. Dùng checklist cuối `LAN-CLUSTER.md` để nghiệm thu trên A và B thật với cùng JAR giao thức 4.
