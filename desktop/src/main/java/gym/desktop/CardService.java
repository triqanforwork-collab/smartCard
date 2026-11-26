package gym.desktop;

import javax.smartcardio.*;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mọi giao tiếp với thẻ đi qua lớp này. Giao diện KHÔNG tự dựng APDU.
 * Hằng số PHẢI KHỚP applet/src/gym/GymApplet.java và docs/APDU.md.
 */
public class CardService {

    // ===== Lệnh APDU =====
    static final byte[] APPLET_AID = {0x47, 0x59, 0x4D, 0x43, 0x41, 0x52, 0x44, 0x01};
    static final int CLA = 0xB0;
    static final int INS_GET_STATUS = 0x01, INS_INIT = 0x10,
            INS_VERIFY_PIN = 0x20, INS_CHANGE_PIN = 0x21, INS_UNBLOCK = 0x22, INS_RESET_PIN = 0x23, INS_CHANGE_ADMIN_PIN = 0x24,
            INS_GET_INFO = 0x30, INS_UPDATE_INFO = 0x31, INS_GET_PHOTO = 0x32, INS_SET_PHOTO = 0x33,
            INS_GET_BALANCE = 0x40, INS_TOPUP = 0x41, INS_PAY = 0x42,
            INS_GET_PUBKEY = 0x50, INS_AUTH = 0x51;
    public static final int PHOTO_CHUNK = 240, MAX_PHOTO = 10240, NONCE_LEN = 16;

    /** Trạng thái thẻ trong GET_STATUS. */
    public static final int STATE_BLANK = 0, STATE_READY = 1, STATE_BLOCKED = 2;

    private Card card;
    private CardChannel channel;

    /** Tên các đầu đọc đang có (đầu đọc ảo của JCIDE chỉ xuất hiện khi JCIDE đang Run). */
    public static List<String> readers() {
        List<String> names = new ArrayList<>();
        try {
            for (CardTerminal t : TerminalFactory.getDefault().terminals().list()) names.add(t.getName());
        } catch (CardException e) {
            // Windows báo lỗi khi không có đầu đọc nào: coi như danh sách rỗng
        }
        return names;
    }

    /** Kết nối đầu đọc và SELECT applet. */
    public void connect(String readerName) throws CardException, CardError {
        disconnect();
        CardTerminal t = TerminalFactory.getDefault().terminals().getTerminal(readerName);
        if (t == null) throw new CardError(0, "Không tìm thấy đầu đọc " + readerName);
        card = t.connect("*");
        channel = card.getBasicChannel();
        check(channel.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID)));
    }

    public boolean isConnected() {
        return channel != null;
    }

    public void disconnect() {
        try {
            if (card != null) card.disconnect(false);
        } catch (CardException ignored) {
        }
        card = null;
        channel = null;
    }

    /** Gửi 1 lệnh tới applet. le = số byte muốn nhận (0 nếu không cần). SW khác 9000 thì ném CardError. */
    byte[] send(int ins, int p1, int p2, byte[] data, int le) throws CardException, CardError {
        if (channel == null) throw new CardError(0, "Chưa kết nối thẻ");
        CommandAPDU c = data == null || data.length == 0
                ? new CommandAPDU(CLA, ins, p1, p2, le)
                : new CommandAPDU(CLA, ins, p1, p2, data, le);
        ResponseAPDU r = channel.transmit(c);
        check(r);
        return r.getData();
    }

    static void check(ResponseAPDU r) throws CardError {
        int sw = r.getSW();
        if (sw != 0x9000) throw new CardError(sw, message(sw));
    }

    /** Đổi mã SW thành câu thông báo cho người dùng. */
    static String message(int sw) {
        if ((sw & 0xFFF0) == 0x63C0) return "Sai mã PIN, còn " + (sw & 0x0F) + " lần thử";
        switch (sw) {
            case 0x6983: return "Mã PIN đã bị khóa do nhập sai quá 3 lần";
            case 0x6982: return "Chưa nhập mã PIN";
            case 0x6985: return "Thẻ chưa khởi tạo, hoặc đã khởi tạo rồi";
            case 0x6910: return "Số dư trên thẻ không đủ";
            case 0x6700: return "Dữ liệu gửi xuống thẻ sai độ dài";
            case 0x6A80: return "Dữ liệu không hợp lệ (PIN phải 4–8 chữ số, số tiền phải lớn hơn 0)";
            case 0x6A82: return "Không tìm thấy applet. JCIDE đã Run chưa, AID có đúng không?";
            case 0x6D00: return "Thẻ không hỗ trợ lệnh này";
            case 0x6E00: return "Sai CLA";
            default: return String.format("Thẻ báo lỗi SW = %04X", sw);
        }
    }

    // ==================== Trạng thái, PIN ====================

    public record Status(int state, int triesLeft) {
        public boolean initialized() { return state != STATE_BLANK; }
        public boolean blocked() { return state == STATE_BLOCKED; }
    }

    public Status getStatus() throws CardException, CardError {
        byte[] d = send(INS_GET_STATUS, 0, 0, null, 256);
        return new Status(d[0], d[1]);
    }

    public boolean isInitialized() throws CardException, CardError {
        return getStatus().initialized();
    }

    /** INIT. Data: [độ dài PIN][PIN][độ dài PIN Admin][PIN Admin][thông tin]. */
    public void init(String pin, String adminPin, MemberInfo info) throws CardException, CardError {
        send(INS_INIT, 0, 0, concat(withLength(pin), withLength(adminPin), info.toBytes()), 0);
    }

    public void verifyPin(String pin) throws CardException, CardError {
        send(INS_VERIFY_PIN, 0, 0, ascii(pin), 0);
    }

    /** CHANGE_PIN. Data: [độ dài PIN cũ][PIN cũ][PIN mới]. Xong vẫn đang đăng nhập. */
    public void changePin(String oldPin, String newPin) throws CardException, CardError {
        send(INS_CHANGE_PIN, 0, 0, concat(withLength(oldPin), ascii(newPin)), 0);
    }

    /** UNBLOCK: Admin mở khóa, PIN người dùng giữ nguyên. */
    public void unblock(String adminPin) throws CardException, CardError {
        send(INS_UNBLOCK, 0, 0, ascii(adminPin), 0);
    }

    /** RESET_PIN: Admin đặt PIN mới cho hội viên quên PIN; dữ liệu trên thẻ giữ nguyên. */
    public void resetPin(String adminPin, String newPin) throws CardException, CardError {
        send(INS_RESET_PIN, 0, 0, concat(withLength(adminPin), ascii(newPin)), 0);
    }

    /** Đổi PIN Admin trên thẻ: thẻ mở khóa AES bằng PIN Admin cũ rồi bọc lại theo PIN Admin mới. */
    public void changeAdminPin(String oldAdminPin, String newAdminPin) throws CardException, CardError {
        send(INS_CHANGE_ADMIN_PIN, 0, 0, concat(withLength(oldAdminPin), ascii(newAdminPin)), 0);
    }

    // ==================== Thông tin, ảnh ====================

    public MemberInfo getInfo() throws CardException, CardError {
        return MemberInfo.fromBytes(send(INS_GET_INFO, 0, 0, null, 256));
    }

    public void updateInfo(MemberInfo info) throws CardException, CardError {
        send(INS_UPDATE_INFO, 0, 0, info.toBytes(), 0);
    }

    /** Đọc ảnh theo từng đoạn 240 byte; chưa có ảnh thì trả mảng rỗng. */
    public byte[] getPhoto() throws CardException, CardError {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int off = 0; off < MAX_PHOTO; off += PHOTO_CHUNK) {
            byte[] part = send(INS_GET_PHOTO, off >> 8, off & 0xFF, null, PHOTO_CHUNK);
            out.writeBytes(part);
            if (part.length < PHOTO_CHUNK) break;
        }
        return out.toByteArray();
    }

    /** Ghi ảnh (tối đa 10240 byte) theo từng đoạn 240 byte. */
    public void setPhoto(byte[] jpeg) throws CardException, CardError {
        if (jpeg.length > MAX_PHOTO) throw new CardError(0, "Ảnh quá lớn: " + jpeg.length + " byte, tối đa " + MAX_PHOTO);
        if (jpeg.length == 0) {
            send(INS_SET_PHOTO, 0, 0, null, 0);
            return;
        }
        for (int off = 0; off < jpeg.length; off += PHOTO_CHUNK) {
            byte[] part = Arrays.copyOfRange(jpeg, off, Math.min(jpeg.length, off + PHOTO_CHUNK));
            send(INS_SET_PHOTO, off >> 8, off & 0xFF, part, 0);
        }
    }

    // ==================== Ví ====================

    /** Số dư (nghìn đồng) và số giao dịch đã thực hiện trên thẻ. */
    public record Balance(long balance, long txCount) {
    }

    /**
     * Kết quả nạp tiền / thanh toán. signedData là chuỗi thẻ đã ký:
     * [INS][số tiền 4][nonce 16][số dư mới 4][số giao dịch 4].
     */
    public record TxResult(long balance, long txCount, byte[] signedData, byte[] signature) {
    }

    public Balance getBalance() throws CardException, CardError {
        byte[] d = send(INS_GET_BALANCE, 0, 0, null, 256);
        return new Balance(uint32(d, 0), uint32(d, 4));
    }

    /** Nạp tiền: thẻ đòi thêm PIN Admin (nhân viên thu tiền), không nằm trong nội dung được ký. */
    public TxResult topUp(long amount, byte[] nonce, String adminPin) throws CardException, CardError {
        return transact(INS_TOPUP, amount, nonce, withLength(adminPin));
    }

    public TxResult pay(long amount, byte[] nonce) throws CardException, CardError {
        return transact(INS_PAY, amount, nonce, new byte[0]);
    }

    private TxResult transact(int ins, long amount, byte[] nonce, byte[] prefix) throws CardException, CardError {
        if (amount <= 0 || amount > 0xFFFFFFFFL) throw new CardError(0, "Số tiền không hợp lệ");
        if (nonce.length != NONCE_LEN) throw new IllegalArgumentException("nonce phải 16 byte");
        byte[] data = concat(int4(amount), nonce);
        byte[] d = send(ins, 0, 0, concat(prefix, data), 256);
        byte[] state = Arrays.copyOf(d, 8);
        return new TxResult(uint32(d, 0), uint32(d, 4),
                concat(new byte[]{(byte) ins}, data, state), Arrays.copyOfRange(d, 8, d.length));
    }

    // ==================== RSA ====================

    public PublicKey getPublicKey() throws CardException, CardError {
        byte[] mod = send(INS_GET_PUBKEY, 0, 0, null, 256);
        byte[] exp = send(INS_GET_PUBKEY, 1, 0, null, 256);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, mod), new BigInteger(1, exp)));
        } catch (GeneralSecurityException e) {
            throw new CardError(0, "Khóa công khai trên thẻ không hợp lệ");
        }
    }

    /** AUTH: thẻ ký [51][nonce]. Trả về chữ ký; chuỗi được ký lấy bằng authData(nonce). */
    public byte[] auth(byte[] nonce) throws CardException, CardError {
        return send(INS_AUTH, 0, 0, nonce, 256);
    }

    public static byte[] authData(byte[] nonce) {
        return concat(new byte[]{(byte) INS_AUTH}, nonce);
    }

    // ==================== Tiện ích ====================

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] withLength(String pin) {
        byte[] p = ascii(pin);
        return concat(new byte[]{(byte) p.length}, p);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    static byte[] int4(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static long uint32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    /** Lỗi từ thẻ. sw = mã phản hồi (0 nếu lỗi phía máy tính). */
    public static class CardError extends Exception {
        public final int sw;

        CardError(int sw, String message) {
            super(message);
            this.sw = sw;
        }
    }
}
