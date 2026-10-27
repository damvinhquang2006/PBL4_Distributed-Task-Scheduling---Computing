# Xác minh bản sửa Web UI qua LAN — 27/10/2026

## Kết quả

- Maven package thành công, 45 kiểm thử: 0 thất bại, 0 lỗi, 0 bỏ qua.
- Sáu kiểm thử mới trong `AsyncMatrixJobTest` chạy qua HTTP và TCP localhost với hai Worker đăng ký riêng.
- Yêu cầu nhân 3×3 với `taskCount=50` chỉ sinh 3 tác vụ có dữ liệu `sliceA`/`dataB`, không có dòng ngoài giới hạn. Cả `laptop-a-worker` và `laptop-b-worker` đều thực sự hoàn thành tác vụ; registry vẫn chỉ có hai Worker.
- Giá trị nhập trực tiếp được dùng kể cả khi descriptor chứa giá trị khác. Kết quả nhân khớp phép nhân tuần tự.
- Nhân ma trận chữ nhật 5×3 và 3×2 từ bộ sinh hằng trả đúng toàn bộ kết quả. Cộng, trừ, modulo, vô hướng, chuyển vị và nghịch đảo khớp thuật toán tuần tự.
- Định thức và hạng ma trận suy biến trả đúng 0 và 1; lỗi nghịch đảo suy biến giữ thông báo lỗi thực tế.
- Kích thước không khớp, rỗng và vượt 1.000.000 phần tử bị từ chối trước khi gửi tác vụ. Khi không có Worker, job timeout và không tự tạo Worker cục bộ.
- `npm run build` trong `frontend` thành công.

## Môi trường kiểm thử

Java 25.0.2, mã nguồn/đích Java 21. Môi trường kiểm thử chặn kết nối Java trong chế độ hạn chế; bộ kiểm thử được chạy với quyền cho phép TCP localhost. Java 25 trên máy này còn gặp lỗi socket AF_UNIX tạm; dùng tùy chọn sau để Java chuyển sang TCP cho kết nối đánh thức nội bộ:

```powershell
mvn -q '-DargLine=-Djdk.net.unixdomain.tmpdir=target/unused-unix-sockets' package
```

`target/unused-unix-sockets` không tồn tại trong lần kiểm thử này. Không thay đổi Java, firewall hoặc cấu hình mạng của máy người dùng. Không cần tùy chọn này cho Worker TCP thông thường.

## Giới hạn xác minh

Đây là kiểm thử HTTP/TCP trên một laptop, chưa phải xác minh Wi-Fi hotspot thực tế với laptop B. Khi thử hai máy, dùng cùng JAR, khởi động lại tiến trình cũ và kiểm tra TCP hai chiều theo `LAN.md`. Không dùng kết quả này làm bằng chứng tăng tốc qua hai laptop.
