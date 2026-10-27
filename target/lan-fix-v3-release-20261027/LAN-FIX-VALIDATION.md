# Xác minh bản sửa Web UI qua LAN — 27/10/2026

## Kết quả

- Maven package thành công, 48 kiểm thử: 0 thất bại, 0 lỗi, 0 bỏ qua.
- Chín kiểm thử trong `AsyncMatrixJobTest` chạy qua HTTP và TCP localhost với hai Worker đăng ký riêng.
- Yêu cầu nhân 3×3 với `taskCount=50` chỉ sinh 3 tác vụ có dữ liệu `sliceA`/`dataB`, không có dòng ngoài giới hạn. Cả `laptop-a-worker` và `laptop-b-worker` đều thực sự hoàn thành tác vụ; registry vẫn chỉ có hai Worker.
- Giá trị nhập trực tiếp được dùng kể cả khi descriptor chứa giá trị khác. Kết quả nhân khớp phép nhân tuần tự.
- Nhân ma trận chữ nhật 5×3 và 3×2 từ bộ sinh hằng trả đúng toàn bộ kết quả. Cộng, trừ, modulo, vô hướng, chuyển vị và nghịch đảo khớp thuật toán tuần tự.
- Định thức và hạng ma trận suy biến trả đúng 0 và 1; lỗi nghịch đảo suy biến giữ thông báo lỗi thực tế.
- Kích thước không khớp, rỗng và vượt 1.000.000 phần tử bị từ chối trước khi gửi tác vụ. Khi không có Worker, job timeout và không tự tạo Worker cục bộ.
- `npm run build` trong `frontend` thành công.
- Bản v2 kiểm tra hợp đồng giao diện cho ma trận nhỏ: `resultSummary.result` phải chứa ma trận đầy đủ khi `isSampled=false`. Trước bản v2, backend chỉ có `sampleGrid` trong summary còn giao diện đọc `result`, khiến bảng kết quả trống dù phép tính thành công.
- Đã mở Web UI bằng trình duyệt, gửi A=[[1,2,3],[4,5,6],[7,8,9]], B=[[9,8,7],[6,5,4],[3,2,1]]. Giao diện hiển thị đủ C=[[30,24,18],[84,69,54],[138,114,90]]. Master và hai Worker `smoke-a`, `smoke-b` là ba tiến trình Java riêng. Log kết quả có cả hai Worker. Ảnh xác minh lưu tại `target/matrix-result-verified.jpg`.
- Script `diagnose-lan.ps1 -MasterPort 15000 -TestMatrix` kiểm tra thành công hai cổng Worker của cụm kiểm thử và trả đúng A×I. Báo cáo tại `target/lan-diagnostic-smoke.json`.
- Khi cả hai Worker đăng ký nhưng PAUSED, job chờ, không gửi tác vụ và lỗi timeout có nêu PAUSED. Web UI hiển thị 0/2 Worker sẵn sàng; nút tính toán bị vô hiệu hóa. Bật lại Worker phục hồi 2/2 và nút tính toán.
- Các tiến trình và tab trình duyệt do kiểm thử tạo đã được dừng/đóng sau khi xác minh.

## Bản v3: 8×8 và 100×100

- API trả đầy đủ 64 ô và 10.000 ô cho hai kích thước này, không lấy mẫu 7×7. Tất cả phần tử của A×I được đối chiếu trong kiểm thử; cả hai Worker có kết quả và registry vẫn chỉ có hai Worker.
- Khi Worker B chuyển OFFLINE, Worker A vẫn hoàn thành 100×100.
- Trình duyệt đã chạy trực tiếp các bài toán 8×8 và 100×100 từ UI với ba tiến trình Java riêng. DOM lần lượt chứa đúng 64 và 10.000 ô số, không rỗng/null. Kết quả hiển thị `smoke-a, smoke-b` là hai Worker thực sự trả kết quả. Ảnh tại `target/matrix-8x8-verified.jpg` và `target/matrix-100x100-verified.jpg`.
- Script `diagnose-lan.ps1 -MasterPort 15000 -TestMatrix -MatrixSize 100` trả `matrixTestPassed=true`, `checkedCells=10000`, `workerIds=[smoke-a,smoke-b]`. Cả hai tiến trình Worker có `jarMatchesMaster=true`. Báo cáo tại `target/lan-v3-100x100-diagnostic.json`.
- Trong lúc kiểm tra không có cụm Master/Worker thật của hai laptop đang chạy trên máy A. Các xác minh ở đây vẫn là localhost; chưa kết luận nguyên nhân lỗi mạng/phiên bản ở laptop B từ mô tả triệu chứng.

## Môi trường kiểm thử

Java 25.0.2, mã nguồn/đích Java 21. Môi trường kiểm thử chặn kết nối Java trong chế độ hạn chế; bộ kiểm thử được chạy với quyền cho phép TCP localhost. Java 25 trên máy này còn gặp lỗi socket AF_UNIX tạm; dùng tùy chọn sau để Java chuyển sang TCP cho kết nối đánh thức nội bộ:

```powershell
mvn -q '-DargLine=-Djdk.net.unixdomain.tmpdir=target/unused-unix-sockets' package
```

`target/unused-unix-sockets` không tồn tại trong lần kiểm thử này. Không thay đổi Java, firewall hoặc cấu hình mạng của máy người dùng. Không cần tùy chọn này cho Worker TCP thông thường.

## Giới hạn xác minh

Đây là kiểm thử HTTP/TCP trên một laptop, chưa phải xác minh Wi-Fi hotspot thực tế với laptop B. Khi thử hai máy, dùng cùng JAR, khởi động lại tiến trình cũ và kiểm tra TCP hai chiều theo `LAN.md`. Không dùng kết quả này làm bằng chứng tăng tốc qua hai laptop.
