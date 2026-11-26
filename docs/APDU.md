# Bảng lệnh APDU – Thẻ hội viên phòng gym

> **Bản chốt (S03, 24/11/2026).** Sửa bảng này thì sửa luôn hằng số ở 3 chỗ: file này, `applet/src/gym/GymApplet.java`, `desktop/src/main/java/gym/desktop/CardService.java`.

## AID

| | Giá trị | Ghi chú |
|---|---|---|
| Package AID | `47594D43415244` | "GYMCARD" |
| Applet AID | `47594D4341524401` | Desktop dùng AID này để `SELECT` |

## Quy ước chung

- **CLA = `B0`**. Số nhiều byte ghi theo thứ tự big-endian.
- **Cần PIN:** phải `VERIFY_PIN` thành công trước, nếu không thẻ trả `6982`.
- Rút thẻ hoặc chọn applet khác thì thẻ quên trạng thái đã nhập PIN và xoá khoá AES khỏi RAM.
- **PIN người dùng** và **PIN Admin** đều là chữ số ASCII dài 4–8 ký tự (PIN `1234` = `31 32 33 34`). Sai 3 lần thì PIN đó bị khoá.

## Danh sách lệnh

### Trạng thái và PIN

| INS | Tên | P1 P2 | Data gửi xuống | Thẻ trả về | Cần PIN |
|---|---|---|---|---|---|
| `01` | GET_STATUS | `00 00` | – | `[trạng thái][số lần thử PIN còn lại]` | Không |
| `10` | INIT | `00 00` | `[độ dài PIN][PIN][độ dài PIN Admin][PIN Admin][thông tin]` | – | Không, chỉ chạy được 1 lần |
| `20` | VERIFY_PIN | `00 00` | `[PIN]` | – | – |
| `21` | CHANGE_PIN | `00 00` | `[độ dài PIN cũ][PIN cũ][PIN mới]` | – | – (tự kiểm tra PIN cũ) |
| `22` | UNBLOCK | `00 00` | `[PIN Admin]` | – | – |
| `23` | RESET_PIN | `00 00` | `[độ dài PIN Admin][PIN Admin][PIN mới]` | – | – |
| `24` | CHANGE_ADMIN_PIN | `00 00` | `[độ dài PIN Admin cũ][PIN Admin cũ][PIN Admin mới]` | – | – (tự kiểm tra PIN Admin cũ) |

- **Trạng thái** trong GET_STATUS: `00` thẻ trắng, `01` đã khởi tạo, `02` PIN người dùng đã bị khoá.
- **UNBLOCK:** Admin mở khoá, PIN người dùng giữ nguyên, số lần thử về lại 3.
- **RESET_PIN:** hội viên quên PIN. Admin đặt PIN mới; thông tin, ảnh và số dư vẫn còn nguyên (xem phần mã hoá).
- **CHANGE_ADMIN_PIN:** đổi PIN Admin của thẻ (khi phòng gym đổi PIN Admin chung). PIN hội viên và dữ liệu giữ nguyên.
- Phần mềm giữ bản băm PBKDF2 của PIN Admin chung (theo phiên bản) và kiểm tra trước khi gửi lệnh có PIN Admin, nên nhập sai trên phần mềm không làm thẻ mất lượt thử. PIN Admin bị khoá (sai 3 lần gửi thẳng tới thẻ) thì không gỡ được: Admin đổi thẻ mới cho hội viên.

### Thông tin và ảnh

| INS | Tên | P1 P2 | Data gửi xuống | Thẻ trả về | Cần PIN |
|---|---|---|---|---|---|
| `30` | GET_INFO | `00 00` | – | `[thông tin]` | Có |
| `31` | UPDATE_INFO | `00 00` | `[thông tin]` | – | Có |
| `32` | GET_PHOTO | offset (2 byte) | – (Le = `F0`) | tối đa 240 byte ảnh tính từ offset | Có |
| `33` | SET_PHOTO | offset (2 byte) | 1 đoạn ảnh, tối đa 240 byte | – | Có |

### Ví (nạp tiền, thanh toán)

| INS | Tên | P1 P2 | Data gửi xuống | Thẻ trả về | Cần PIN |
|---|---|---|---|---|---|
| `40` | GET_BALANCE | `00 00` | – | `[số dư 4][số giao dịch 4]` | Có |
| `41` | TOPUP | `00 00` | `[độ dài PIN Admin][PIN Admin][số tiền 4][nonce 16]` | `[số dư mới 4][số giao dịch 4][chữ ký 128]` | Có, **và PIN Admin** |
| `42` | PAY | `00 00` | `[số tiền 4][nonce 16]` | `[số dư mới 4][số giao dịch 4][chữ ký 128]` | Có |

- Đơn vị tiền: **nghìn đồng**, số không dấu 4 byte. Số tiền phải lớn hơn 0.
- **Số giao dịch** tăng 1 sau mỗi lần nạp hoặc thanh toán thành công, dùng để phát hiện giao dịch bị gửi lại.
- PAY khi không đủ tiền trả `6910`, số dư không đổi.
- **TOPUP đòi thêm PIN Admin** (nạp tiền là việc của nhân viên thu tiền): PIN Admin sai trả `63Cx`, PIN Admin bị khóa trả `6983`. Nhờ vậy chương trình tự gửi lệnh nạp tiền xuống thẻ mà không biết PIN Admin sẽ bị từ chối, kể cả khi đã có PIN của hội viên. PIN Admin không nằm trong nội dung được ký.

### RSA

| INS | Tên | P1 P2 | Data gửi xuống | Thẻ trả về | Cần PIN |
|---|---|---|---|---|---|
| `50` | GET_PUBKEY | P1 = `00` modulus, `01` exponent | – | modulus 128 byte, hoặc exponent | Không |
| `51` | AUTH | `00 00` | `[nonce 16]` | `[chữ ký 128]` | Không |

- AUTH dùng để kiểm tra thẻ thật trước khi đăng nhập: máy tính gửi nonce ngẫu nhiên, thẻ ký, máy tính kiểm tra bằng khoá công khai đã lưu trong CSDL lúc khởi tạo.

## Chữ ký RSA

- Cặp khoá **RSA-1024** sinh trên thẻ lúc INIT, khoá riêng không bao giờ ra khỏi thẻ.
- Thuật toán: **RSA PKCS#1 v1.5 kèm SHA-1** (`Signature.ALG_RSA_SHA_PKCS1` trên thẻ, `SHA1withRSA` trong Java). Java Card 2.2.2 chưa có RSA kèm SHA-256.
- Nội dung được ký:

| Lệnh | Nội dung ký |
|---|---|
| AUTH | `[51][nonce 16]` |
| TOPUP / PAY | `[INS 1][số tiền 4][nonce 16][số dư mới 4][số giao dịch 4]` (29 byte) |

Máy tính tự dựng lại đúng chuỗi trên rồi kiểm tra chữ ký. Chữ ký sai hoặc nonce không khớp thì không ghi giao dịch.

## Mã hoá dữ liệu trên thẻ (AES-128)

- Lúc INIT, thẻ sinh ngẫu nhiên **khoá chính K** (16 byte). Thông tin, ảnh và số dư đều lưu ở dạng mã hoá bằng K, không có bản rõ nào trong EEPROM.
- **Khoá sinh từ PIN:** `KEK = 16 byte đầu của SHA-256(salt ‖ PIN)`, salt 16 byte ngẫu nhiên tạo lúc INIT.
- Thẻ lưu 2 bản K đã bọc (AES, 1 khối 16 byte): một bản bằng KEK của PIN người dùng, một bản bằng KEK của PIN Admin (salt riêng).
- **VERIFY_PIN** đúng thì thẻ mở bọc K và giữ trong RAM (tự xoá khi rút thẻ).
- **CHANGE_PIN** chỉ bọc lại K bằng PIN mới. **RESET_PIN** mở K bằng PIN Admin rồi bọc lại bằng PIN mới của người dùng. **CHANGE_ADMIN_PIN** mở K bằng PIN Admin cũ rồi bọc lại bằng PIN Admin mới (salt mới).
- Thông tin: AES-CBC, IV ngẫu nhiên tạo mới mỗi lần ghi.
- Ảnh: mỗi đoạn 240 byte (đúng 15 khối AES) mã hoá AES-CBC riêng, IV = IV của ảnh với 2 byte cuối XOR số thứ tự đoạn.
- Số dư: 1 khối 16 byte `[số dư 4][số giao dịch 4][8 byte 0]`, mã hoá AES. Cập nhật trong một giao dịch Java Card (`beginTransaction`/`commitTransaction`) để không hỏng khi rút thẻ giữa chừng.

## Định dạng dữ liệu

**Thông tin hội viên:** tối đa **200 byte**. Desktop ghép 6 trường bằng ký tự xuống dòng `\n` (`0A`), mã UTF-8, theo thứ tự:

```
maHV \n hoTen \n ngaySinh \n sdt \n goiTap \n hanGoi
```

Lớp `MemberInfo.java` bên desktop ghép và tách sẵn. Thẻ lưu nguyên khối, không tách trường.

**Ảnh:** JPEG đã thu nhỏ, tối đa **10240 byte**.
- Ghi: `SET_PHOTO` lần lượt với offset `0, 240, 480…`. Offset phải là bội số của 240; offset `0` nghĩa là bắt đầu ảnh mới. Độ dài ảnh = offset + số byte của đoạn cuối cùng.
- Đọc: `GET_PHOTO` với offset `0, 240, 480…`, Le = `F0`. Thẻ trả **ít hơn 240 byte** (có thể 0) là đã hết ảnh.

## Mã phản hồi (SW)

| SW | Ý nghĩa |
|---|---|
| `9000` | Thành công |
| `63Cx` | Sai PIN (hoặc PIN Admin), còn `x` lần thử |
| `6983` | PIN đã bị khoá |
| `6982` | Chưa nhập PIN |
| `6985` | Sai điều kiện: thẻ chưa khởi tạo, hoặc gọi INIT lần 2 |
| `6910` | Không đủ số dư để thanh toán |
| `6700` | Sai độ dài dữ liệu |
| `6A80` | Dữ liệu sai: PIN không phải 4–8 chữ số, số tiền bằng 0, số dư vượt giới hạn, offset ảnh sai |
| `6A82` | Không tìm thấy applet (sai AID hoặc JCIDE chưa Run) |
| `6D00` | INS không tồn tại |
| `6E00` | Sai CLA (phải là `B0`) |

## Thử bằng Debug Shell của JCIDE

```
/select 47594D4341524401      -> 90 00
/send B001000000              -> 00 03 90 00   (thẻ trắng, còn 3 lần thử)
```
