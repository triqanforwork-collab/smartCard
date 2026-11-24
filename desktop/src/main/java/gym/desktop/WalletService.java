package gym.desktop;

import javax.smartcardio.CardException;
import java.security.PublicKey;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * Nghiệp vụ ví: nạp tiền, thanh toán gia hạn gói tập.
 * Mỗi giao dịch: máy tính tạo nonce, thẻ cập nhật số dư và ký, máy tính kiểm tra chữ ký
 * bằng khóa công khai trong CSDL. Chỉ ghi giao dịch vào CSDL khi chữ ký đúng.
 */
public class WalletService {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CardService card;
    private final Database db;

    public WalletService(CardService card, Database db) {
        this.card = card;
        this.db = db;
    }

    public CardService.Balance balance() throws CardException, CardService.CardError {
        return card.getBalance();
    }

    /** Nạp tiền (nghìn đồng). Thẻ đòi PIN Admin; phần mềm còn đòi Admin đăng nhập (kiểm tra ở màn hình). */
    public CardService.TxResult topUp(String maHV, long amount, String adminPin)
            throws CardException, CardService.CardError, SQLException {
        return deposit(maHV, amount, adminPin, Database.TOPUP, "Nạp tiền tại quầy");
    }

    /** Cộng tiền vào thẻ (nạp tiền, hoặc chuyển số dư khi cấp lại thẻ). */
    CardService.TxResult deposit(String maHV, long amount, String adminPin, String type, String note)
            throws CardException, CardService.CardError, SQLException {
        byte[] nonce = MemberService.nonce();
        CardService.TxResult r;
        try {
            r = card.topUp(amount, nonce, adminPin);
        } catch (CardService.CardError e) {
            db.log(maHV, type + " bị thẻ từ chối", e.getMessage(), true);
            throw e;
        }
        record(maHV, type, amount, nonce, r, note);
        return r;
    }

    /**
     * Thanh toán gói tập: trừ tiền trên thẻ, rồi gia hạn: hạn mới = (hạn cũ nếu còn hiệu lực, không thì hôm nay)
     * cộng số tháng của gói. Trả về thông tin đã cập nhật (đã ghi lên thẻ và CSDL).
     */
    public MemberInfo payPackage(MemberInfo member, Database.GymPackage pkg)
            throws CardException, CardService.CardError, SQLException {
        byte[] nonce = MemberService.nonce();
        CardService.TxResult r = card.pay(pkg.price(), nonce);
        record(member.maHV, Database.PAY, pkg.price(), nonce, r, "Gia hạn " + pkg.name());

        member.goiTap = pkg.name();
        member.hanGoi = newExpiry(member.hanGoi, pkg.months(), LocalDate.now()).format(DATE);
        card.updateInfo(member);
        db.updateMember(member);
        return member;
    }

    /** Hạn mới: nếu hạn cũ còn hiệu lực thì cộng tiếp từ hạn cũ, ngược lại cộng từ hôm nay. */
    static LocalDate newExpiry(String oldExpiry, int months, LocalDate today) {
        LocalDate from = today;
        try {
            LocalDate old = LocalDate.parse(oldExpiry, DATE);
            if (old.isAfter(today)) from = old;
        } catch (DateTimeParseException | NullPointerException ignored) {
            // chưa có hạn hoặc sai định dạng: tính từ hôm nay
        }
        return from.plusMonths(months);
    }

    /** Kiểm tra giao dịch thẻ trả về rồi ghi vào CSDL; sai thì ghi nhật ký cảnh báo, báo lỗi, không ghi giao dịch. */
    private void record(String maHV, String type, long amount, byte[] nonce, CardService.TxResult r, String note)
            throws CardService.CardError, SQLException {
        PublicKey key = db.publicKey(maHV);
        if (key == null) {
            throw new CardService.CardError(0, "Không có khóa công khai của " + maHV + " trong CSDL, không kiểm tra được giao dịch");
        }
        byte[] d = r.signedData();
        boolean sameRequest = Arrays.equals(Arrays.copyOfRange(d, 1, 5), CardService.int4(amount))
                && Arrays.equals(Arrays.copyOfRange(d, 5, 21), nonce);
        if (!sameRequest || !MemberService.verify(key, d, r.signature())) {
            db.log(maHV, type + ": chữ ký không hợp lệ", "Giao dịch " + amount + " nghìn đồng không được ghi nhận", true);
            throw new CardService.CardError(0, "Chữ ký giao dịch không hợp lệ, giao dịch không được ghi nhận");
        }
        if (r.txCount() <= db.lastTxCount(maHV)) {
            db.log(maHV, type + ": giao dịch gửi lại", "Số thứ tự " + r.txCount(), true);
            throw new CardService.CardError(0, "Số thứ tự giao dịch " + r.txCount() + " đã được ghi trước đó (giao dịch bị gửi lại?)");
        }
        db.addTransaction(maHV, type, amount, r.balance(), r.txCount(), note, nonce, r.signature(), true);
        db.log(maHV, type, amount + " nghìn đồng, số dư " + r.balance(), false);
    }
}
