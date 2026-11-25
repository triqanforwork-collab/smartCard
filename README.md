# Thẻ hội viên phòng gym – Smart Card 2026

Applet Java Card lưu thông tin, ảnh và ví của hội viên (mã hóa AES bằng khóa sinh từ PIN, chữ ký RSA cho mỗi giao dịch)
và phần mềm quầy lễ tân trên máy tính (Java Swing, CSDL SQLite hoặc MySQL).

Hướng dẫn chi tiết từng màn hình: [docs/HDSD_The_hoi_vien_Gym.docx](docs/HDSD_The_hoi_vien_Gym.docx).
Kịch bản demo: [docs/KICH_BAN_DEMO.md](docs/KICH_BAN_DEMO.md). Bảng lệnh APDU: [docs/APDU.md](docs/APDU.md).

## Cấu trúc

```
gym-card/
├── applet/                 project JCIDE (mở GymApplet.jcsln), Java Card 2.2.2
│   └── src/gym/GymApplet.java
├── desktop/                phần mềm máy tính (Maven, mở bằng NetBeans)
│   ├── db.properties.example   mẫu cấu hình MySQL
│   └── src/main/java/gym/desktop/
│       ├── CardService.java     gửi lệnh APDU tới thẻ
│       ├── MemberService.java   phát hành, đăng nhập, đối chiếu số dư, báo mất / cấp lại, check-in
│       ├── WalletService.java   nạp tiền, thanh toán gói, kiểm tra chữ ký RSA
│       ├── Database.java        CSDL: hội viên, giao dịch, gói tập, Admin, nhật ký, check-in
│       ├── MainFrame.java       cửa sổ chính, quyền Admin
│       └── screens/             16 màn hình (JPanel Form)
├── tools/                  chương trình kiểm thử
└── docs/                   HDSD, kịch bản demo, bảng APDU
```

## Cài đặt và chạy

Cần: JDK 17+, NetBeans, JCIDE (cài vào `C:\JavaCardKit`). Thư mục `gym-card` phải nằm ở đường dẫn không dấu, không dấu cách.

1. **Thẻ ảo:** trong JCIDE vào **Tool → Options → Run/Debug**, tick **JCVM Started by IDE** và **Enable PCSC Interface** (làm 1 lần).
   Mở `applet/GymApplet.jcsln`, bấm **Ctrl+F7** rồi **Run**, đợi dòng `You can send apdu with PC/SC interface now`.
2. **Phần mềm:** NetBeans → **Open Project** → `desktop`, bấm **F6**.
   Chọn đầu đọc **JAVACOS Virtual Contact Reader 0** → **Kết nối**. Lần đầu bấm **Quản trị** để tạo tài khoản Admin.

Mỗi lần bấm Run trong JCIDE là một thẻ trắng mới.

### Dùng MySQL (tùy chọn)

Mặc định dữ liệu nằm trong file `desktop/gym.db` (SQLite, tự tạo). Muốn dùng MySQL:

1. `CREATE DATABASE gym CHARACTER SET utf8mb4;`
2. Chép `desktop/db.properties.example` thành `desktop/db.properties`, điền mật khẩu.
3. Chạy phần mềm, các bảng tự tạo. Xóa `db.properties` là quay về SQLite.

`db.properties` đã nằm trong `.gitignore`. Dữ liệu trong `gym.db` không tự chuyển sang MySQL.

## Kiểm thử

| Lệnh (chạy trong `gym-card`) | Kết quả |
|---|---|
| `java tools/AppletTest.java` | 100/100, 15 lệnh APDU (cần thẻ trắng) |
| `java -cp "%CP%" tools/ScenarioTest.java` | 54/54, kịch bản đầy đủ qua các lớp dịch vụ (cần thẻ trắng) |
| `java -cp "%CP%" tools/ScenarioTest.java reissue` | 9/9, cấp lại thẻ (bấm Run lại trong JCIDE trước) |
| `java -cp "%CP%" tools/DbCheck.java [url user mật_khẩu]` | 14/14, lớp CSDL, không cần thẻ; có url thì chạy trên MySQL (tên CSDL phải chứa `test`) |
| `java tools/FakeCard.java HV100 "Lê Văn An" 1234` | tạo thẻ giả để demo phát hiện thẻ giả |

`%CP%` (sau khi build trong NetBeans):
```
set M=%USERPROFILE%/.m2/repository
set CP=desktop/target/classes;%M%/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar;%M%/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar;%M%/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar
```

## Quy tắc khi sửa code

- Đổi lệnh APDU thì sửa cùng lúc `docs/APDU.md`, `GymApplet.java`, `CardService.java`.
- Applet: comment không dấu (JCIDE dùng windows-1252), chỉ dùng `byte`/`short`, ép kiểu `(short) (a + b)`.
- Giao diện không tự dựng APDU, chỉ gọi `CardService` / các lớp Service.
- Màn hình sửa bằng tab **Design** của NetBeans; không sửa tay `initComponents`; commit cả `.form` lẫn `.java`.
