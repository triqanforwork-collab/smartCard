package gym.desktop;

import javax.smartcardio.CardException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.function.UnaryOperator;

/**
 * Nghiệp vụ hội viên: phát hành thẻ, đăng nhập (kiểm tra thẻ thật bằng RSA, thẻ đã báo mất),
 * đối chiếu số dư, sửa thông tin, đổi PIN, mở khóa, đặt lại PIN, cấp lại thẻ, check-in.
 * Mỗi thao tác làm trên thẻ trước, rồi ghi CSDL và nhật ký.
 */
public class MemberService {
    private final CardService card;
    private final Database db;
    static final SecureRandom RANDOM = new SecureRandom();

    public MemberService(CardService card, Database db) {
        this.card = card;
        this.db = db;
    }

    /**
     * Phát hành thẻ mới: khởi tạo thẻ, ghi ảnh, lưu hội viên và khóa công khai RSA của thẻ vào CSDL.
     * Xong thì thẻ đang ở trạng thái đã nhập PIN.
     */
    public void issueCard(String pin, String adminPin, MemberInfo info, byte[] photo)
            throws CardException, CardService.CardError, SQLException {
        if (db.memberExists(info.maHV)) {
            throw new CardService.CardError(0, "Mã hội viên " + info.maHV + " đã có trong CSDL");
        }
        prepareAdminPin(null, adminPin, null);
        card.init(pin, adminPin, info);
        card.verifyPin(pin);
        if (photo != null && photo.length > 0) card.setPhoto(photo);
        db.addMember(info, card.getPublicKey());
        db.log(info.maHV, "Phát hành thẻ", info.hoTen, false);
    }

    /**
     * Đăng nhập: nhập PIN, đọc thông tin, rồi kiểm tra thẻ:
     * thẻ đã báo mất bị từ chối; thẻ thật phải ký đúng nonce (AUTH) bằng khóa khớp khóa công khai trong CSDL.
     */
    public MemberInfo login(String pin) throws CardException, CardService.CardError, SQLException {
        card.verifyPin(pin);
        MemberInfo info = card.getInfo();
        PublicKey key = db.publicKey(info.maHV);
        if (key == null) {
            db.log(info.maHV, "Thẻ chưa đăng ký", "Thẻ mang mã " + info.maHV + " không có trong CSDL", true);
            throw new CardService.CardError(0, "Thẻ của " + info.maHV + " chưa được đăng ký trong CSDL phòng gym");
        }
        if (db.isLostCard(info.maHV, card.getPublicKey())) {
            db.log(info.maHV, "Dùng thẻ đã báo mất", "Thẻ bị từ chối", true);
            card.disconnect();
            throw new CardService.CardError(0, "Thẻ này đã bị vô hiệu (báo mất hoặc đã đổi thẻ), không sử dụng được. Liên hệ quầy để được cấp lại thẻ.");
        }
        byte[] nonce = nonce();
        if (!verify(key, CardService.authData(nonce), card.auth(nonce))) {
            db.log(info.maHV, "Phát hiện thẻ giả", "Chữ ký AUTH không khớp khóa công khai đã đăng ký", true);
            card.disconnect();
            throw new CardService.CardError(0, "Thẻ không hợp lệ: chữ ký không khớp khóa công khai đã đăng ký (thẻ giả?)");
        }
        return info;
    }

    /**
     * Đối chiếu số dư và số giao dịch trên thẻ với CSDL (thẻ phải đang đăng nhập).
     * Khớp thì trả null; lệch thì ghi nhật ký cảnh báo và trả câu cảnh báo.
     */
    public String reconcile(String maHV) throws CardException, CardService.CardError, SQLException {
        CardService.Balance onCard = card.getBalance();
        Database.Expected inDb = db.expected(maHV);
        if (onCard.balance() == inDb.balance() && onCard.txCount() == inDb.txCount()) return null;
        String msg = String.format("Số dư trên thẻ %d (giao dịch số %d) khác với CSDL %d (giao dịch số %d). "
                        + "Có giao dịch không được ghi nhận hoặc thẻ đã bị can thiệp.",
                onCard.balance(), onCard.txCount(), inDb.balance(), inDb.txCount());
        db.log(maHV, "Số dư lệch", msg, true);
        return msg;
    }

    /** Sửa thông tin trên thẻ và cập nhật bản sao trong CSDL. Mã hội viên không đổi. */
    public void updateInfo(MemberInfo info) throws CardException, CardService.CardError, SQLException {
        card.updateInfo(info);
        db.updateMember(info);
        db.log(info.maHV, "Sửa thông tin", null, false);
    }

    public void changePin(String maHV, String oldPin, String newPin) throws CardException, CardService.CardError {
        card.changePin(oldPin, newPin);
        db.log(maHV, "Đổi PIN", null, false);
    }

    /**
     * Kiểm tra PIN Admin với bản băm trong CSDL TRƯỚC khi gửi xuống thẻ: sai thì báo lỗi ngay, thẻ không bị trừ lượt thử.
     * Nếu thẻ của hội viên còn mang PIN Admin phiên bản cũ (Admin đã đổi PIN sau khi phát hành thẻ) thì hỏi PIN cũ
     * qua askOldPin rồi đổi PIN Admin trên thẻ sang PIN hiện tại (lệnh CHANGE_ADMIN_PIN).
     * maHV = null: thẻ trắng (phát hành, cấp lại), chỉ kiểm tra PIN.
     */
    public void prepareAdminPin(String maHV, String adminPin, UnaryOperator<String> askOldPin)
            throws CardException, CardService.CardError, SQLException {
        int cur = db.adminPinVersion();
        if (cur == 0) {
            throw new CardService.CardError(0, "Chưa đặt PIN Admin của phòng gym (Quản trị → Đổi PIN Admin)");
        }
        if (!db.checkAdminPin(adminPin, cur)) {
            db.log(maHV, "Sai PIN Admin", "Bị chặn trước khi gửi xuống thẻ", true);
            throw new CardService.CardError(0, "PIN Admin không đúng (thẻ không bị trừ lượt thử)");
        }
        int ver = maHV == null ? cur : db.memberAdminPinVersion(maHV);
        if (ver >= cur) return;
        String old = askOldPin == null ? null : askOldPin.apply("Thẻ của " + maHV + " được phát hành trước lần đổi PIN Admin gần nhất "
                + "nên còn dùng PIN Admin cũ.\nNhập PIN Admin cũ để cập nhật thẻ sang PIN Admin hiện tại:");
        if (old == null || old.isBlank()) {
            throw new CardService.CardError(0, "Thẻ của " + maHV + " còn dùng PIN Admin cũ. Không nhớ PIN cũ thì dùng "
                    + "Đổi thẻ (thẻ hỏng / khóa PIN Admin) ở Danh sách hội viên.");
        }
        if (!db.checkAdminPin(old, ver)) {
            db.log(maHV, "Sai PIN Admin cũ", "Phiên bản " + ver + ", bị chặn trước khi gửi xuống thẻ", true);
            throw new CardService.CardError(0, "PIN Admin cũ không đúng (thẻ không bị trừ lượt thử)");
        }
        card.changeAdminPin(old, adminPin);
        db.setMemberAdminPinVersion(maHV, cur);
        db.log(maHV, "Cập nhật PIN Admin trên thẻ", "Phiên bản " + ver + " → " + cur, false);
    }

    /** Mã hội viên của thẻ đang cắm, tra theo khóa công khai (không cần PIN hội viên). */
    private String cardOwner() throws CardException, CardService.CardError, SQLException {
        return db.memberByKey(card.getPublicKey());
    }

    /** Admin mở khóa thẻ bằng PIN Admin (thẻ đang khóa nên tra hội viên theo khóa công khai của thẻ). */
    public void unblock(String adminPin, UnaryOperator<String> askOldPin)
            throws CardException, CardService.CardError, SQLException {
        String maHV = cardOwner();
        prepareAdminPin(maHV, adminPin, askOldPin);
        try {
            card.unblock(adminPin);
            db.log(maHV, "Mở khóa thẻ", null, false);
        } catch (CardService.CardError e) {
            db.log(maHV, "Mở khóa thẻ thất bại", e.getMessage(), true);
            throw e;
        }
    }

    /** Admin đặt lại PIN cho hội viên quên PIN. */
    public void resetPin(String adminPin, String newPin, UnaryOperator<String> askOldPin)
            throws CardException, CardService.CardError, SQLException {
        String maHV = cardOwner();
        prepareAdminPin(maHV, adminPin, askOldPin);
        try {
            card.resetPin(adminPin, newPin);
            db.log(maHV, "Đặt lại PIN", null, false);
        } catch (CardService.CardError e) {
            db.log(maHV, "Đặt lại PIN thất bại", e.getMessage(), true);
            throw e;
        }
    }

    /**
     * Cấp lại thẻ cho hội viên đã báo mất thẻ hoặc thẻ hỏng: khởi tạo thẻ trắng với thông tin cũ, thay khóa công khai trong CSDL,
     * rồi nạp vào thẻ mới đúng số dư CSDL ghi nhận cho thẻ cũ (giao dịch "Chuyển số dư", có chữ ký RSA).
     */
    public void reissueCard(String pin, String adminPin, MemberInfo info, byte[] photo)
            throws CardException, CardService.CardError, SQLException {
        String status = db.memberStatus(info.maHV);
        if (!Database.LOST.equals(status) && !Database.BROKEN.equals(status)) {
            throw new CardService.CardError(0, "Chỉ cấp lại thẻ cho hội viên đã báo mất thẻ hoặc thẻ hỏng");
        }
        prepareAdminPin(null, adminPin, null);
        long oldBalance = db.expected(info.maHV).balance();
        card.init(pin, adminPin, info);
        card.verifyPin(pin);
        if (photo != null && photo.length > 0) card.setPhoto(photo);
        db.reissue(info, card.getPublicKey());
        if (oldBalance > 0) {
            new WalletService(card, db).deposit(info.maHV, oldBalance, adminPin, Database.TRANSFER, "Chuyển số dư từ thẻ đã báo mất");
        }
        db.log(info.maHV, "Cấp lại thẻ", "Chuyển số dư " + oldBalance + " nghìn đồng sang thẻ mới", false);
    }

    /** Kết quả một lần quẹt thẻ ở quầy: vào (checkIn = true, kèm số ngày còn lại) hoặc ra (kèm thời gian tập). */
    public record Swipe(MemberInfo member, boolean checkIn, long daysLeft, Duration stayed) {
    }

    /** Gói còn từ chừng này ngày trở xuống thì nhắc gia hạn khi check-in. */
    public static final int REMIND_DAYS = 7;
    /** Lượt vào quá chừng này giờ (hoặc sang ngày khác) mà chưa check-out thì coi như quên check-out, tự đóng. */
    public static final int MAX_STAY_HOURS = 6;
    /** Hai lần quẹt cách nhau dưới chừng này thì bỏ qua lần sau (lỡ tay quẹt 2 lần). */
    static final Duration DEBOUNCE = Duration.ofMinutes(1);

    /**
     * Quẹt thẻ ở quầy, không cần PIN (thẻ không phải giải mã dữ liệu, chỉ cần chứng minh là thẻ thật):
     * đọc khóa công khai (GET_PUBKEY) → tra CSDL ra hội viên, chặn thẻ đã vô hiệu → thẻ ký nonce (AUTH) đúng khóa
     * đã đăng ký. Sau đó: đang trong phòng thì check-out; đang ở ngoài thì check-in (gói phải còn hạn).
     * Chống mượn thẻ: người đang trong phòng thì thẻ không check-in thêm được (quẹt lần nữa là check-out).
     */
    public Swipe swipe(LocalDateTime now) throws CardException, CardService.CardError, SQLException {
        if (!card.isInitialized()) throw new IllegalStateException("Thẻ chưa được phát hành");
        PublicKey key = card.getPublicKey();
        String disabled = db.disabledCardOwner(key);
        if (disabled != null) {
            db.log(disabled, "Dùng thẻ đã báo mất", "Check-in bị từ chối", true);
            throw new IllegalStateException("Thẻ này đã bị vô hiệu (báo mất hoặc đã đổi thẻ). Mời ra quầy.");
        }
        String maHV = db.memberByKey(key);
        if (maHV == null) {
            db.log(null, "Thẻ chưa đăng ký", "Check-in bị từ chối", true);
            throw new IllegalStateException("Thẻ chưa được đăng ký tại phòng gym");
        }
        byte[] nonce = nonce();
        if (!verify(key, CardService.authData(nonce), card.auth(nonce))) {
            db.log(maHV, "Phát hiện thẻ giả", "Check-in: chữ ký AUTH không khớp khóa công khai đã đăng ký", true);
            throw new IllegalStateException("Thẻ không hợp lệ (thẻ giả?)");
        }
        MemberInfo m = db.member(maHV);
        String hhmm = "HH:mm";
        Database.Visit v = db.lastVisit(maHV);
        if (v != null && !v.closed()) {   // đang trong phòng
            Duration stay = Duration.between(v.in(), now);
            if (!stay.isNegative() && stay.compareTo(DEBOUNCE) < 0) {
                throw new IllegalStateException(m.hoTen + " vừa check-in lúc " + v.in().format(DateTimeFormatter.ofPattern(hhmm)));
            }
            if (stay.toHours() < MAX_STAY_HOURS && v.in().toLocalDate().equals(now.toLocalDate())) {
                db.checkOut(v.id(), now);
                db.log(maHV, "Check-out", "Tập " + stay.toMinutes() + " phút", false);
                return new Swipe(m, false, 0, stay);
            }
            db.checkOut(v.id(), null);   // quên check-out: đóng lượt cũ, lần quẹt này tính là check-in mới
            db.log(maHV, "Quên check-out", "Lượt vào lúc " + v.in().format(Database.TIME) + " được đóng tự động", false);
        } else if (v != null && v.out() != null && !now.isBefore(v.out()) && Duration.between(v.out(), now).compareTo(DEBOUNCE) < 0) {
            throw new IllegalStateException(m.hoTen + " vừa check-out lúc " + v.out().format(DateTimeFormatter.ofPattern(hhmm)));
        }
        LocalDate today = now.toLocalDate();
        LocalDate e = Database.expiry(m.hanGoi);
        if (e == null || e.isBefore(today)) {
            db.log(maHV, "Check-in bị từ chối", e == null ? "Chưa có gói tập" : "Gói hết hạn ngày " + m.hanGoi, false);
            throw new IllegalStateException(e == null ? m.hoTen + " chưa có gói tập. Mời ra quầy gia hạn."
                    : "Gói của " + m.hoTen + " đã hết hạn ngày " + m.hanGoi + ". Mời ra quầy gia hạn.");
        }
        db.addCheckIn(maHV, now);
        db.log(maHV, "Check-in", null, false);
        return new Swipe(m, true, ChronoUnit.DAYS.between(today, e), Duration.ZERO);
    }

    static byte[] nonce() {
        byte[] n = new byte[CardService.NONCE_LEN];
        RANDOM.nextBytes(n);
        return n;
    }

    /** Kiểm tra chữ ký RSA PKCS#1 kèm SHA-1 (khớp Signature.ALG_RSA_SHA_PKCS1 trên thẻ). */
    static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature v = Signature.getInstance("SHA1withRSA");
            v.initVerify(key);
            v.update(data);
            return v.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
