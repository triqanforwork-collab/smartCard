package gym.desktop;

import javax.smartcardio.CardException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.sql.SQLException;
import java.time.LocalDate;
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

    /** Kết quả check-in: hội viên và số ngày còn lại của gói. */
    public record CheckIn(MemberInfo member, long daysLeft) {
    }

    /** Check-in vào phòng tập: đăng nhập (thẻ thật, chưa báo mất), gói còn hạn thì ghi lượt tập. */
    public CheckIn checkIn(String pin, LocalDate today) throws CardException, CardService.CardError, SQLException {
        MemberInfo m = login(pin);
        reconcile(m.maHV); // chỉ ghi nhật ký nếu lệch, không chặn việc vào tập
        LocalDate e = Database.expiry(m.hanGoi);
        if (e == null || e.isBefore(today)) {
            db.log(m.maHV, "Check-in bị từ chối", e == null ? "Chưa có gói tập" : "Gói hết hạn ngày " + m.hanGoi, false);
            throw new IllegalStateException(e == null ? "Hội viên chưa có gói tập. Vui lòng gia hạn gói."
                    : "Gói tập đã hết hạn ngày " + m.hanGoi + ". Vui lòng gia hạn gói.");
        }
        db.addCheckIn(m.maHV);
        db.log(m.maHV, "Check-in", null, false);
        return new CheckIn(m, ChronoUnit.DAYS.between(today, e));
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
