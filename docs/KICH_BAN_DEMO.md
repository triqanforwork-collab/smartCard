# Kịch bản demo – Thẻ hội viên phòng gym

Thời lượng khoảng 15 phút. Mỗi bước ghi thao tác, kết quả phải thấy, và ý cần nói khi thuyết trình.

## Chuẩn bị (trước khi lên trình bày)

1. Mở JCIDE, mở `gym-card\applet\GymApplet.jcsln`, bấm **Build** rồi **Run** (Debug Shell hiện `You can send apdu with PC/SC interface now`).
2. Làm CSDL trống: xoá file `gym-card/desktop/gym.db` (SQLite), hoặc nếu dùng MySQL thì chạy `DROP DATABASE gym; CREATE DATABASE gym CHARACTER SET utf8mb4;`.
3. Mở NetBeans, chạy project `gym-card/desktop` (F6).
4. Có sẵn ảnh `docs/anh-mau.jpg` để chọn làm ảnh hội viên.

Dữ liệu dùng trong demo:

| | Giá trị |
|---|---|
| Mã hội viên | `HV001` (phần mềm tự sinh, CSDL trống) |
| Họ tên | Lê Văn An |
| Ngày sinh | 15/08/2003 |
| Số điện thoại | 0912345678 |
| PIN ban đầu | `1234` |
| PIN Admin chung của phòng gym | `87654321` (đổi thành `13572468` ở bước 25) |
| Tài khoản Admin (phần mềm) | `admin` / `matkhau123` |

## Các bước

| # | Thao tác | Kết quả phải thấy | Ý cần nói |
|---|---|---|---|
| 1 | Chọn đầu đọc **JAVACOS Virtual Contact Reader 0**, bấm **Kết nối** (chưa đăng nhập Admin) | Báo "Đây là thẻ trắng. Admin cần đăng nhập để phát hành thẻ." | Phát hành thẻ là việc của Admin; phần mềm xác thực theo từng chức năng |
| 2 | Bấm **Quản trị** → nhập `admin`, mật khẩu `matkhau123` 2 lần, PIN Admin `87654321` 2 lần → **Tạo tài khoản** | Hiện **mã khôi phục** (chép lại), rồi sang màn **Quản trị**: thống kê 0 hội viên | Mật khẩu, PIN Admin chung và mã khôi phục đều lưu trong CSDL dạng băm PBKDF2-HMAC-SHA256, salt 16 byte, 100 000 vòng. Mã khôi phục dùng khi quên mật khẩu / quên PIN Admin |
| 3 | Bấm **Về màn Kết nối** → **Kết nối** | Sang màn **Khởi tạo thẻ** | Thẻ trắng: GET_STATUS trả `00` |
| 4 | Tiêu đề màn hình hiện "Khởi tạo thẻ – mã HV001" (mã tự sinh, không có ô nhập mã). Nhập thông tin, cố tình gõ số điện thoại `12345`, bấm **Khởi tạo thẻ** | Báo "Số điện thoại phải bắt đầu bằng 0…" | Phần mềm kiểm tra dữ liệu trước khi gửi xuống thẻ |
| 5 | Sửa số điện thoại, **Chọn ảnh** `anh-mau.jpg`, nhập PIN 2 lần và PIN Admin, bấm **Khởi tạo thẻ** (màn này không có ô Gói tập, Hạn gói: được ghi khi thanh toán gói) | "Khởi tạo thẻ thành công. Mã hội viên: HV001", sang màn **Thông tin**, có ảnh, số dư 0 | Thẻ tự sinh khóa AES K và cặp khóa RSA. K được bọc bằng khóa sinh từ PIN (SHA-256). Ảnh được nén dưới 10 KB. Khóa công khai RSA lưu vào CSDL |
| 6 | **Nạp tiền** 2000, nhập PIN Admin sai `11112222` | "PIN Admin không đúng (thẻ không bị trừ lượt thử)", số dư vẫn 0 | Phần mềm so PIN Admin với bản băm trong CSDL trước khi gửi, nên nhập nhầm không làm khóa thẻ. Thẻ vẫn tự đòi PIN Admin khi nạp tiền: chương trình tự gửi lệnh nạp tiền mà không biết PIN Admin bị thẻ từ chối (AppletTest có thử) |
| 7 | Nhập lại PIN Admin đúng `87654321`, bấm **Nạp tiền** | "Đã nạp 2000… Chữ ký RSA hợp lệ" | Thẻ cộng số dư (đã mã hóa), ký giao dịch; máy tính kiểm tra chữ ký bằng khóa công khai rồi mới ghi CSDL |
| 8 | **Gia hạn gói** → chọn Gói 3 tháng → Thanh toán → Yes | Số dư 1200, hạn mới = hôm nay + 3 tháng | Hội viên tự thanh toán bằng số dư (chỉ cần PIN); giao dịch cũng ký RSA |
| 9 | Chọn Gói 12 tháng (2800) → Thanh toán | "Số dư trên thẻ không đủ", số dư vẫn 1200 | Thẻ tự kiểm tra số dư, trả mã lỗi 6910 |
| 10 | Quay lại, bấm **Lịch sử** | 2 giao dịch, cột Chữ ký "Hợp lệ" | Lịch sử nằm trong CSDL, có số thứ tự giao dịch chống gửi lại |
| 11 | **Đổi mã PIN**: 1234 → 5678 | "Đã đổi mã PIN" | Thẻ chỉ bọc lại K theo PIN mới, dữ liệu không phải mã hóa lại |
| 12 | **Đăng xuất**, **Kết nối**, nhập PIN cũ `1234` 3 lần | Lần 1: "còn 2 lần", lần 2: "còn 1 lần", lần 3: "Mã PIN đã bị khóa" | OwnerPIN giới hạn 3 lần thử |
| 13 | Bấm **Thẻ bị khóa / quên PIN?** → nhập PIN Admin → **Mở khóa**, rồi đăng nhập bằng `5678` | Vào được màn Thông tin, dữ liệu còn nguyên | Admin (đã đăng nhập phần mềm) mở khóa bằng PIN Admin, PIN hội viên giữ nguyên |
| 14 | Đăng xuất, Kết nối, bấm **Thẻ bị khóa / quên PIN?** → PIN Admin + PIN mới `2468` → **Đặt lại PIN**, đăng nhập bằng `2468` | Thông tin, ảnh, số dư 1200 vẫn còn | Thẻ lưu sẵn 1 bản K bọc bằng PIN Admin nên quên PIN không mất dữ liệu |
| 15 | Đăng xuất, bấm **Check-in** (không nhập gì) | Tự hiện chữ xanh "MỜI VÀO – Lê Văn An, gói còn 92 ngày". Chờ hơn 1 phút, bấm **Quẹt lại**: chữ xanh dương "TẠM BIỆT – Lê Văn An, đã tập 1 phút". Quản trị hiện "Đang trong phòng" theo đúng trạng thái | Quẹt thẻ không cần PIN: thẻ ký chuỗi ngẫu nhiên bằng RSA (chặn thẻ giả), CSDL cho biết thẻ chưa bị vô hiệu và gói còn hạn. Đang ở ngoài thì quẹt là check-in, đang trong phòng thì quẹt là check-out. Chống mượn thẻ: người đang trong phòng thì thẻ không check-in thêm được (đưa thẻ cho người khác quẹt chỉ là check-out). Quên check-out quá 6 giờ thì tự đóng |
| 16 | Bấm **Quản trị** | Thống kê: 1 hội viên còn hạn, 1 check-in hôm nay, nạp tháng này 2000, có cảnh báo (Sai PIN Admin ở bước 6) | Màn tổng quan cho chủ phòng gym, lấy hết từ CSDL |
| 17 | **Gói tập** → thêm "Gói 2 tháng", 2 tháng, 550 → **Thêm**; chọn dòng đó → **Ngừng/mở bán** | Gói mới hiện trong bảng, trạng thái "Ngừng bán"; màn Gia hạn gói không còn thấy gói này | Admin tự quản lý bảng giá, gói ngừng bán vẫn giữ trong lịch sử |
| 18 | Quay lại → **Danh sách hội viên**, gõ "An", **Tìm** | Thấy HV001, Gói 3 tháng, Còn hạn, Thẻ số 1, 1 lượt tập | Chỉ Admin tra cứu được, không cần thẻ |
| 19 | Về màn Kết nối, bấm **Quản trị** → **Đăng xuất Admin**; Kết nối, đăng nhập `2468`, bấm **Nạp tiền** | Báo "Chức năng này cần Admin đăng nhập" | Hội viên không tự nạp tiền, không xem được danh sách người khác |
| 20 | **Thẻ giả**: trong JCIDE bấm Run lại (thẻ trắng khác), chạy `java tools/FakeCard.java HV001 "Lê Văn An" 1234`, rồi Kết nối và đăng nhập `1234` | "Thẻ không hợp lệ: chữ ký không khớp khóa công khai đã đăng ký", quay về màn Kết nối | Thẻ giả chép được thông tin nhưng không có khóa riêng RSA của thẻ thật, nên không ký đúng được |
| 21 | **Báo mất**: Quản trị (đăng nhập lại `admin`) → Danh sách hội viên → chọn HV001 → **Báo mất thẻ** → Yes | Cột Thẻ: "Đã báo mất" | Thẻ thật đã mất ở bước 20 (Run lại simulator). Khóa công khai cũ bị đưa vào danh sách thẻ mất, ai nhặt được thẻ cũ cũng bị từ chối |
| 22 | **Cấp lại thẻ**: chọn HV001 → **Cấp lại thẻ**; trong JCIDE bấm Run lại; **Kết nối** | Màn "Cấp lại thẻ cho HV001", thông tin điền sẵn, mã HV khóa | Chỉ cấp lại cho hội viên đã báo mất hoặc thẻ hỏng. Thẻ hỏng / khóa PIN Admin thì dùng nút **Đổi thẻ hỏng**, quy trình giống hệt |
| 23 | Chọn ảnh, nhập PIN `1357` 2 lần, PIN Admin `87654321` → **Khởi tạo thẻ** | "Đã cấp lại thẻ… Số dư của thẻ cũ đã được chuyển sang thẻ mới", số dư 1200, gói và hạn gói giữ nguyên | Số dư lấy từ CSDL (giao dịch cuối có chữ ký hợp lệ của thẻ cũ), nạp vào thẻ mới bằng giao dịch "Chuyển số dư" cũng ký RSA |
| 24 | Quản trị → **Nhật ký**, chọn "Chỉ cảnh báo" | Thấy: Sai PIN Admin, Phát hiện thẻ giả, Báo mất thẻ | Mọi thao tác đều ghi nhật ký kèm Admin thực hiện; cảnh báo lọc riêng |
| 25 | Quản trị → **Đổi PIN Admin**: PIN hiện tại `87654321`, PIN mới `13572468` 2 lần → **Đổi PIN Admin**. Về màn Kết nối, Kết nối, đăng nhập `1357`, **Nạp tiền** 100 với PIN Admin `13572468` | Phần mềm hỏi PIN Admin cũ: nhập `87654321` → nạp thành công | Đổi PIN Admin chung khi nhân viên nghỉ việc. CSDL lưu PIN theo phiên bản; thẻ cũ được đổi sang PIN mới bằng lệnh CHANGE_ADMIN_PIN ngay lần đầu Admin thao tác, dữ liệu giữ nguyên |
| 26 | (Nói, không cần chờ) | | **Quên mật khẩu**: màn Đăng nhập Admin → Quên mật khẩu, nhập mã khôi phục. **Quên PIN Admin**: màn Đổi PIN Admin → Quên PIN Admin, cũng bằng mã khôi phục. Admin không thao tác 5 phút thì tự đăng xuất. Màn Nhập PIN có nút **Quay lại** để rút thẻ cắm nhầm |

## Kiểm thử tự động (chạy trước buổi demo để chắc chắn)

Mỗi lệnh cần thẻ trắng (bấm Run lại trong JCIDE trước khi chạy). Chạy từ thư mục `gym-card`, build `desktop` trước:

```
java tools/AppletTest.java
```
117 phép thử cho 16 lệnh APDU của applet (gồm nạp tiền đòi PIN Admin, đổi PIN Admin).

```
java -cp "desktop/target/classes;%USERPROFILE%/.m2/repository/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar;%USERPROFILE%/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar" tools/ScenarioTest.java
```
Lượt 1, 69 phép thử: cả kịch bản trên qua các lớp dịch vụ, PIN Admin chung (chặn sai trước khi gửi xuống thẻ), đổi PIN Admin và cập nhật thẻ cũ, đối chiếu số dư, check-in / check-out không PIN, quên check-out, thẻ giả, giao dịch gửi lại, nhật ký, thống kê, gói tập, đổi mật khẩu Admin, báo mất thẻ.

Lượt 2: bấm Run lại trong JCIDE (thẻ trắng mới), chạy lại đúng lệnh trên kèm tham số `reissue` ở cuối. 9 phép thử: cấp lại thẻ và chuyển số dư.
