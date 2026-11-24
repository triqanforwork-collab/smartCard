# Thẻ hội viên phòng gym – Smart Card 2026

Applet Java Card + phần mềm quản lý trên máy tính (Swing, SQLite). Đầu việc theo dõi ở Google Sheet, tab *Kế hoạch hoàn thiện*.

## Cấu trúc và ai sửa file nào

```
gym-card/
├── docs/APDU.md                       bảng lệnh APDU, định dạng dữ liệu, mã lỗi      Quân (C03), Tài (C04)
├── applet/GymApplet.java              applet chạy trên thẻ (JCIDE, Java Card 2.2.2)   Quân: PIN · Tài: INIT, thông tin, ảnh
└── desktop/                           phần mềm trên máy tính (NetBeans, Maven)
    └── src/main/java/gym/desktop/
        ├── CardService.java           mọi lệnh gửi tới thẻ                            Tuấn Anh (T02, T03, T04)
        ├── MemberInfo.java            ghép/tách thông tin hội viên
        ├── MemberService.java         phát hành, đăng nhập (thẻ thật/thẻ mất), đối chiếu số dư, PIN, cấp lại thẻ, check-in
        ├── WalletService.java         nạp tiền, thanh toán gói, kiểm tra chữ ký RSA
        ├── Database.java              SQLite: hội viên, giao dịch, gói tập, Admin, nhật ký, check-in, thống kê
        ├── MainFrame.java             cửa sổ chính, quyền Admin, tự đăng xuất Admin sau 5 phút
        └── screens/                   các màn hình                                    Quyền (G01–G05)
```

**Trạng thái:** đã làm xong cả applet (15 lệnh) và phần mềm (16 màn hình). Kiểm thử: `tools/AppletTest.java` 100/100, `tools/ScenarioTest.java` 54/54 + 9/9 (cách chạy ở `docs/KICH_BAN_DEMO.md`).

## Cài đặt (mỗi người làm 1 lần)

1. **JCIDE:** vào **Tool → Options → Run/Debug**, tick ☑ **JCVM Started by IDE** và ☑ **Enable PCSC Interface**.
2. **NetBeans** (bản mới, chạy được với JDK 17 trở lên). NetBeans có sẵn Maven, không cần cài thêm.

## Chạy thử

### 1. Applet trong JCIDE

Project JCIDE nằm ở `C:\workspace\GymApplet` (đường dẫn không dấu, không dấu cách, nếu không JCIDE báo lỗi build).

1. Mở `C:\workspace\GymApplet\GymApplet.jcsln` bằng JCIDE.
2. Cấu hình đã đặt sẵn, chỉ cần kiểm tra nếu build lỗi (chuột phải project → **Properties**):
   - **JCkit Configuration → Conveter:** `FTSAFE:ft305_for_classic` (JCIDE bản mới không còn `ft222`).
   - **Library Option:** có `java_card_kit-2_2_2`.
   - Package `gym` / `47594D43415244`, applet `GymApplet` / `47594D4341524401`.
3. Chọn project, bấm **Ctrl+F7** để build.
4. Bấm **Run**, đợi Debug Shell hiện `You can send apdu with PC/SC interface now`.
   Nếu JCIDE hỏi *"You haven't added any package…"*: bấm OK → **Add package** chọn `gym` → chọn dòng `gym` → **Install applet** chọn `GymApplet` → Run.
5. Thử trong Debug Shell:
   ```
   /select 47594D4341524401      -> 90 00
   /send B001000000              -> 00 90 00
   ```

**Quy trình sửa applet:** sửa trong JCIDE → Build → Run → chép lại vào `applet/GymApplet.java` để đưa lên GitHub.

### 2. Phần mềm trong NetBeans

1. **File → Open Project**, chọn thư mục `gym-card/desktop` (NetBeans nhận ra đây là project Maven).
2. Bấm **Run** (F6). Lần đầu NetBeans tải thư viện FlatLaf nên hơi lâu.
3. Chọn đầu đọc **JAVACOS Virtual Contact Reader 0**, bấm **Kết nối**.
   - Thẻ trắng: cần Admin đăng nhập (nút **Quản trị**) rồi app chuyển sang màn **Khởi tạo thẻ**.
   - Thẻ đã phát hành: sang màn nhập PIN. Các bước demo đầy đủ ở `docs/KICH_BAN_DEMO.md`.

**Giao diện làm bằng kéo thả:** mọi màn trong `screens/` đều là JPanel Form (`xxxForm.java` + `xxxForm.form`); mở trong NetBeans sẽ có tab **Design** để sửa giao diện.
- Không sửa tay phần code màu xám (`initComponents`), NetBeans sẽ ghi đè.
- Làm màn mới: chuột phải package `gym.desktop.screens` → **New → JPanel Form** (không dùng JFrame Form, vì app chỉ có một cửa sổ), rồi làm theo 3 bước ghi ở đầu file `PinForm.java`.
- Đưa lên GitHub thì đưa cả file `.form` lẫn `.java`.

## Quy tắc chung

- **Đổi lệnh APDU thì sửa 3 chỗ cùng lúc:** `docs/APDU.md`, `GymApplet.java`, `CardService.java`.
- **Applet viết comment không dấu**, vì JCIDE biên dịch bằng encoding windows-1252. Phần desktop viết có dấu bình thường.
- **Applet chỉ dùng `byte` và `short`** (Java Card 2.2.2 không có `int`, `String`). Phép cộng trừ phải ép kiểu: `(short) (a + b)`.
- **Giao diện không tự dựng APDU,** chỉ gọi hàm trong `CardService`.
