# PBL4 LAN cluster release

Xem LAN-CLUSTER.md để chạy 50 Worker trên A và 50 Worker trên B.

- Java 21+ trên cả hai máy. A cần Node.js để chạy UI; B chỉ cần Java.
- Dừng Master/Worker cũ rồi dùng cùng JAR trong gói này. Giao thức 3 bắt buộc thông tin laptop.
- Master trên A: scripts/start-lan-master.ps1.
- Mỗi laptop: scripts/start-lan-workers.ps1 với MasterAddress, WorkerAddress và MachineId khác nhau (laptop-a/laptop-b).
- Khuyến nghị -StartPort 5101 nếu cổng 5040 trong dải mặc định đang bận.
- UI trên A: scripts/start-ui.ps1. UI chỉ hiển thị Worker đăng ký, tự cập nhật và nhóm theo laptop.
- Chẩn đoán trên A: scripts/diagnose-lan.ps1 -TestMatrix -MatrixSize 100.

57 kiểm thử đã vượt qua. Ảnh và báo cáo ở validation/. Đây là xác minh mô phỏng trên một máy; nghiệm thu Wi-Fi hai laptop thật theo checklist trong LAN-CLUSTER.md.