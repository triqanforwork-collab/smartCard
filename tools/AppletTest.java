import javax.smartcardio.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Kiểm thử GymApplet trên thẻ ảo JCIDE: gửi từng lệnh APDU và so SW, dữ liệu trả về với mong đợi.
 *
 * Cách chạy (cần thẻ trắng, tức là vừa bấm Run trong JCIDE):
 *     java tools/AppletTest.java
 *
 * Chương trình khởi tạo thẻ ở đầu, nên muốn chạy lại thì bấm Run trong JCIDE để có thẻ trắng.
 */
public class AppletTest {
    static final byte[] AID = {0x47, 0x59, 0x4D, 0x43, 0x41, 0x52, 0x44, 0x01};
    static final String PIN = "1234", ADMIN = "87654321";
    static final byte[] INFO = "HV001\nNguyễn Văn Quân\n01/02/2004\n0912345678\nGói 3 tháng\n31/03/2027".getBytes(StandardCharsets.UTF_8);
    static final byte[] INFO2 = "HV001\nNguyễn Văn Quân\n01/02/2004\n0987654321\nGói 6 tháng\n30/06/2027".getBytes(StandardCharsets.UTF_8);

    static Card card;
    static CardChannel ch;
    static int pass, fail;

    public static void main(String[] args) throws Exception {
        List<CardTerminal> ts = TerminalFactory.getDefault().terminals().list();
        CardTerminal t = ts.stream().filter(x -> x.getName().contains("Contact Reader")).findFirst().orElse(ts.get(0));
        card = t.connect("*");
        ch = card.getBasicChannel();
        expect("SELECT GymApplet", select(), 0x9000);

        ResponseAPDU st = send(0x01, null);
        if (st.getSW() == 0x9000 && st.getData()[0] != 0) {
            System.out.println("Thẻ không còn trắng. Bấm Run trong JCIDE rồi chạy lại.");
            return;
        }

        section("A01, A02: trước khi khởi tạo");
        expectData("GET_STATUS thẻ trắng", send(0x01, null), 0x9000, bytes(0, 3));
        expect("VERIFY_PIN khi chưa khởi tạo", send(0x20, ascii(PIN)), 0x6985);
        expect("GET_INFO khi chưa khởi tạo", send(0x30, null), 0x6985);
        expect("INIT với PIN 3 số", send(0x10, initData("123", ADMIN, INFO)), 0x6A80);
        expect("INIT với PIN có chữ", send(0x10, initData("12a4", ADMIN, INFO)), 0x6A80);
        expect("INIT với PIN Admin 9 số", send(0x10, initData(PIN, "123456789", INFO)), 0x6A80);
        expect("INIT với thông tin 201 byte", send(0x10, initData(PIN, ADMIN, new byte[201])), 0x6700);
        expect("GET_PUBKEY khi chưa khởi tạo", sendP(0x50, 0, null), 0x6985);

        section("A02: khởi tạo thẻ");
        expect("INIT hợp lệ", send(0x10, initData(PIN, ADMIN, INFO)), 0x9000);
        expectData("GET_STATUS sau khởi tạo", send(0x01, null), 0x9000, bytes(1, 3));
        expect("INIT lần 2 bị từ chối", send(0x10, initData(PIN, ADMIN, INFO)), 0x6985);

        section("A01: xác thực PIN");
        expect("GET_INFO khi chưa nhập PIN", send(0x30, null), 0x6982);
        expect("VERIFY_PIN sai lần 1", send(0x20, ascii("0000")), 0x63C2);
        expectData("GET_STATUS còn 2 lần", send(0x01, null), 0x9000, bytes(1, 2));
        expect("VERIFY_PIN đúng", send(0x20, ascii(PIN)), 0x9000);
        expectData("GET_STATUS về lại 3 lần", send(0x01, null), 0x9000, bytes(1, 3));

        section("A03: thông tin hội viên (mã hóa AES bằng K mở từ PIN)");
        expectData("GET_INFO giải mã đúng thông tin lúc INIT", send(0x30, null), 0x9000, INFO);
        expect("UPDATE_INFO thông tin mới", send(0x31, INFO2), 0x9000);
        expectData("GET_INFO trả thông tin mới", send(0x30, null), 0x9000, INFO2);
        expect("UPDATE_INFO 201 byte", send(0x31, new byte[201]), 0x6700);
        expect("SELECT lại (giống rút thẻ ra cắm lại)", select(), 0x9000);
        expect("GET_INFO sau khi chọn lại applet cần nhập lại PIN", send(0x30, null), 0x6982);
        expect("VERIFY_PIN đúng", send(0x20, ascii(PIN)), 0x9000);
        expectData("GET_INFO vẫn là thông tin mới", send(0x30, null), 0x9000, INFO2);

        section("A04: ảnh (mã hóa AES từng đoạn 240 byte)");
        java.util.Random rnd = new java.util.Random(7);
        byte[] small = new byte[580];
        rnd.nextBytes(small);
        expect("SET_PHOTO 580 byte (3 đoạn)", writePhoto(small), 0x9000);
        expectData("GET_PHOTO đọc lại đúng 580 byte", readPhoto(), 0x9000, small);
        expectData("GET_PHOTO offset sau cuối ảnh trả rỗng", sendP(0x32, 720, null), 0x9000, new byte[0]);
        expect("SET_PHOTO offset không chia hết 240", sendP(0x33, 100, new byte[16]), 0x6A80);
        expect("SET_PHOTO bỏ cách đoạn (offset 960)", sendP(0x33, 960, new byte[16]), 0x6A80);
        expect("SET_PHOTO đoạn 241 byte", sendP(0x33, 0, new byte[241]), 0x6700);
        byte[] full = new byte[10240];
        rnd.nextBytes(full);
        expect("SET_PHOTO ảnh tối đa 10240 byte", writePhoto(full), 0x9000);
        expectData("GET_PHOTO đọc lại đúng 10240 byte", readPhoto(), 0x9000, full);
        expect("SET_PHOTO vượt 10240 byte", sendP(0x33, 10080, new byte[200]), 0x6A80);
        byte[] photo = new byte[1000];
        rnd.nextBytes(photo);
        expect("SET_PHOTO ảnh mới 1000 byte thay ảnh cũ", writePhoto(photo), 0x9000);
        expectData("GET_PHOTO trả ảnh mới", readPhoto(), 0x9000, photo);
        expect("SELECT lại", select(), 0x9000);
        expect("GET_PHOTO khi chưa nhập PIN", sendP(0x32, 0, null), 0x6982);
        expect("SET_PHOTO khi chưa nhập PIN", sendP(0x33, 0, new byte[16]), 0x6982);

        section("A06: RSA-1024, khóa công khai và xác thực thẻ");
        expect("SELECT lại (AUTH không cần PIN)", select(), 0x9000);
        ResponseAPDU mod = sendP(0x50, 0x0000, null), exp = sendP(0x50, 0x0100, null);
        report("GET_PUBKEY modulus 128 byte", mod.getSW() == 0x9000 && mod.getData().length == 128,
                String.format("SW %04X, %d byte", mod.getSW(), mod.getData().length));
        expect("GET_PUBKEY exponent", exp, 0x9000);
        pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, mod.getData()), new BigInteger(1, exp.getData())));
        byte[] nonce = randomBytes(16);
        ResponseAPDU au = send(0x51, nonce);
        report("AUTH: chữ ký của [51][nonce] kiểm tra đúng", au.getSW() == 0x9000 && verify(concat(bytes(0x51), nonce), au.getData()),
                String.format("SW %04X, chữ ký %d byte", au.getSW(), au.getData().length));
        report("AUTH: đổi nonce thì chữ ký không khớp", !verify(concat(bytes(0x51), randomBytes(16)), au.getData()), "");
        expect("AUTH nonce 15 byte", send(0x51, new byte[15]), 0x6700);

        section("A07: ví (số dư mã hóa, nạp tiền, thanh toán có chữ ký)");
        expect("GET_BALANCE khi chưa nhập PIN", send(0x40, null), 0x6982);
        expect("TOPUP khi chưa nhập PIN", send(0x41, topup(ADMIN, 100, randomBytes(16))), 0x6982);
        expect("VERIFY_PIN đúng", send(0x20, ascii(PIN)), 0x9000);
        expectData("GET_BALANCE thẻ mới: 0 đồng, 0 giao dịch", send(0x40, null), 0x9000, bytes(0, 0, 0, 0, 0, 0, 0, 0));
        expect("A09: TOPUP không kèm PIN Admin (chương trình tự gửi) bị từ chối", send(0x41, money(100, randomBytes(16))), 0x6700);
        expect("A09: TOPUP với PIN Admin sai bị từ chối", send(0x41, topup("11112222", 100, randomBytes(16))), 0x63C2);
        expectData("A09: số dư không đổi sau 2 lần nạp bị từ chối", send(0x40, null), 0x9000, bytes(0, 0, 0, 0, 0, 0, 0, 0));
        checkTx("TOPUP 500", 0x41, 500, 500, 1);
        checkTx("PAY 200", 0x42, 200, 300, 2);
        expect("PAY 301 khi chỉ còn 300", send(0x42, money(301, randomBytes(16))), 0x6910);
        expectData("Số dư không đổi sau khi PAY bị từ chối", send(0x40, null), 0x9000, bal(300, 2));
        expect("TOPUP 0 đồng", send(0x41, topup(ADMIN, 0, randomBytes(16))), 0x6A80);
        expect("TOPUP thiếu nonce", send(0x41, concat(pair(ascii(ADMIN), new byte[0]), new byte[4])), 0x6700);
        expect("TOPUP làm số dư vượt 4 byte", send(0x41, topup(ADMIN, 0xFFFFFFFFL, randomBytes(16))), 0x6A80);
        expect("SELECT lại", select(), 0x9000);
        expect("VERIFY_PIN đúng", send(0x20, ascii(PIN)), 0x9000);
        expectData("Số dư còn nguyên sau khi chọn lại applet", send(0x40, null), 0x9000, bal(300, 2));

        section("A05: đổi PIN");
        expect("CHANGE_PIN sai PIN cũ", send(0x21, pair(ascii("0000"), ascii("5678"))), 0x63C2);
        expect("CHANGE_PIN với PIN mới 2 số (không trừ lượt)", send(0x21, pair(ascii(PIN), ascii("12"))), 0x6A80);
        expectData("GET_STATUS còn 2 lần", send(0x01, null), 0x9000, bytes(1, 2));
        expect("CHANGE_PIN 1234 -> 5678", send(0x21, pair(ascii(PIN), ascii("5678"))), 0x9000);
        expectData("GET_STATUS về lại 3 lần", send(0x01, null), 0x9000, bytes(1, 3));
        expectData("Vẫn đăng nhập: GET_INFO đọc được", send(0x30, null), 0x9000, INFO2);
        expect("SELECT lại", select(), 0x9000);
        expect("VERIFY_PIN bằng PIN cũ", send(0x20, ascii(PIN)), 0x63C2);
        expect("VERIFY_PIN bằng PIN mới", send(0x20, ascii("5678")), 0x9000);
        expectData("Thông tin còn nguyên sau khi đổi PIN", send(0x30, null), 0x9000, INFO2);
        expectData("Ảnh còn nguyên sau khi đổi PIN", readPhoto(), 0x9000, photo);

        section("A05: khóa thẻ rồi Admin mở khóa");
        expect("SELECT lại", select(), 0x9000);
        expect("VERIFY_PIN sai lần 1", send(0x20, ascii("1111")), 0x63C2);
        expect("VERIFY_PIN sai lần 2", send(0x20, ascii("2222")), 0x63C1);
        expect("VERIFY_PIN sai lần 3 -> khóa", send(0x20, ascii("3333")), 0x6983);
        expectData("GET_STATUS báo bị khóa", send(0x01, null), 0x9000, bytes(2, 0));
        expect("VERIFY_PIN đúng nhưng thẻ đã khóa", send(0x20, ascii("5678")), 0x6983);
        expect("GET_INFO khi thẻ khóa", send(0x30, null), 0x6982);
        expect("UNBLOCK sai PIN Admin", send(0x22, ascii("11112222")), 0x63C2);
        expect("UNBLOCK đúng PIN Admin", send(0x22, ascii(ADMIN)), 0x9000);
        expectData("GET_STATUS hết khóa, còn 3 lần", send(0x01, null), 0x9000, bytes(1, 3));
        expect("VERIFY_PIN bằng PIN cũ (5678) vẫn dùng được", send(0x20, ascii("5678")), 0x9000);
        expectData("Thông tin còn nguyên sau khi mở khóa", send(0x30, null), 0x9000, INFO2);

        section("A05: quên PIN, Admin đặt lại PIN");
        expect("SELECT lại", select(), 0x9000);
        expect("RESET_PIN sai PIN Admin", send(0x23, pair(ascii("11112222"), ascii("2468"))), 0x63C2);
        expect("RESET_PIN với PIN mới có chữ", send(0x23, pair(ascii(ADMIN), ascii("24a8"))), 0x6A80);
        expect("RESET_PIN đúng PIN Admin, PIN mới 2468", send(0x23, pair(ascii(ADMIN), ascii("2468"))), 0x9000);
        expect("Sau RESET_PIN chưa đăng nhập: GET_INFO", send(0x30, null), 0x6982);
        expect("VERIFY_PIN bằng PIN trước đó (5678)", send(0x20, ascii("5678")), 0x63C2);
        expect("VERIFY_PIN bằng PIN mới 2468", send(0x20, ascii("2468")), 0x9000);
        expectData("Thông tin còn nguyên sau khi đặt lại PIN", send(0x30, null), 0x9000, INFO2);
        expectData("Ảnh còn nguyên sau khi đặt lại PIN", readPhoto(), 0x9000, photo);
        expectData("Số dư còn nguyên sau khi đặt lại PIN", send(0x40, null), 0x9000, bal(300, 2));
        report("Khóa RSA không đổi sau khi đặt lại PIN", Arrays.equals(sendP(0x50, 0, null).getData(), mod.getData()), "");

        System.out.printf("%nKẾT QUẢ: %d đạt, %d không đạt%n", pass, fail);
        card.disconnect(false);
        if (fail > 0) System.exit(1);
    }

    // ---------- tiện ích ----------

    static int select() throws CardException {
        return ch.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, AID)).getSW();
    }

    static ResponseAPDU send(int ins, byte[] data) throws CardException {
        CommandAPDU c = data == null || data.length == 0
                ? new CommandAPDU(0xB0, ins, 0, 0, 256)
                : new CommandAPDU(0xB0, ins, 0, 0, data, 256);
        return ch.transmit(c);
    }

    static PublicKey pub;

    /** Gửi TOPUP/PAY, kiểm tra số dư mới, số giao dịch và chữ ký RSA. */
    static void checkTx(String name, int ins, long amount, long newBal, long count) throws Exception {
        byte[] nonce = randomBytes(16);
        byte[] data = money(amount, nonce);
        ResponseAPDU r = send(ins, ins == 0x41 ? concat(pair(ascii(ADMIN), new byte[0]), data) : data);
        byte[] d = r.getData();
        boolean ok = r.getSW() == 0x9000 && d.length == 136 && Arrays.equals(Arrays.copyOf(d, 8), bal(newBal, count));
        report(name + String.format(": số dư %d, giao dịch thứ %d", newBal, count), ok,
                String.format("SW %04X, %s", r.getSW(), short_(Arrays.copyOf(d, Math.min(8, d.length)))));
        if (d.length == 136) {
            byte[] sig = Arrays.copyOfRange(d, 8, 136);
            byte[] msg = concat(bytes(ins), data, Arrays.copyOf(d, 8));
            report(name + ": chữ ký RSA kiểm tra đúng", verify(msg, sig), "");
            byte[] fake = msg.clone();
            fake[4] ^= 1; // sửa số tiền
            report(name + ": sửa số tiền thì chữ ký không khớp", !verify(fake, sig), "");
        }
    }

    static boolean verify(byte[] msg, byte[] sig) throws Exception {
        Signature v = Signature.getInstance("SHA1withRSA");
        v.initVerify(pub);
        v.update(msg);
        return v.verify(sig);
    }

    /** Dữ liệu TOPUP: [độ dài PIN Admin][PIN Admin][số tiền 4][nonce 16]. */
    static byte[] topup(String admin, long amount, byte[] nonce) {
        return concat(pair(ascii(admin), new byte[0]), money(amount, nonce));
    }

    static byte[] money(long amount, byte[] nonce) {
        return concat(int4(amount), nonce);
    }

    static byte[] bal(long balance, long count) {
        return concat(int4(balance), int4(count));
    }

    static byte[] int4(long v) {
        return bytes((int) (v >>> 24) & 0xFF, (int) (v >>> 16) & 0xFF, (int) (v >>> 8) & 0xFF, (int) v & 0xFF);
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    static ResponseAPDU sendP(int ins, int p1p2, byte[] data) throws CardException {
        CommandAPDU c = data == null || data.length == 0
                ? new CommandAPDU(0xB0, ins, p1p2 >> 8, p1p2 & 0xFF, 256)
                : new CommandAPDU(0xB0, ins, p1p2 >> 8, p1p2 & 0xFF, data, 256);
        return ch.transmit(c);
    }

    /** Ghi cả ảnh theo từng đoạn 240 byte; trả SW của đoạn lỗi đầu tiên hoặc 9000. */
    static int writePhoto(byte[] img) throws CardException {
        for (int off = 0; off < img.length; off += 240) {
            int sw = sendP(0x33, off, Arrays.copyOfRange(img, off, Math.min(img.length, off + 240))).getSW();
            if (sw != 0x9000) return sw;
        }
        return 0x9000;
    }

    /** Đọc cả ảnh; dừng khi thẻ trả ít hơn 240 byte. Trả về ResponseAPDU giả với dữ liệu ghép lại. */
    static ResponseAPDU readPhoto() throws CardException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int off = 0; ; off += 240) {
            ResponseAPDU r = sendP(0x32, off, null);
            if (r.getSW() != 0x9000) return r;
            out.writeBytes(r.getData());
            if (r.getData().length < 240) break;
        }
        byte[] d = out.toByteArray();
        byte[] full = Arrays.copyOf(d, d.length + 2);
        full[d.length] = (byte) 0x90;
        return new ResponseAPDU(full);
    }

    /** [độ dài a][a][b], dùng cho CHANGE_PIN và RESET_PIN. */
    static byte[] pair(byte[] a, byte[] b) {
        byte[] d = new byte[1 + a.length + b.length];
        d[0] = (byte) a.length;
        System.arraycopy(a, 0, d, 1, a.length);
        System.arraycopy(b, 0, d, 1 + a.length, b.length);
        return d;
    }

    static byte[] initData(String pin, String admin, byte[] info) {
        byte[] p = ascii(pin), a = ascii(admin);
        byte[] d = new byte[2 + p.length + a.length + info.length];
        d[0] = (byte) p.length;
        System.arraycopy(p, 0, d, 1, p.length);
        d[1 + p.length] = (byte) a.length;
        System.arraycopy(a, 0, d, 2 + p.length, a.length);
        System.arraycopy(info, 0, d, 2 + p.length + a.length, info.length);
        return d;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static void section(String name) { System.out.println("\n== " + name); }

    static void expect(String name, int sw, int want) {
        report(name, sw == want, String.format("SW %04X", sw) + (sw == want ? "" : String.format(", mong đợi %04X", want)));
    }

    static void expect(String name, ResponseAPDU r, int want) { expect(name, r.getSW(), want); }

    static void expectData(String name, ResponseAPDU r, int want, byte[] data) {
        boolean ok = r.getSW() == want && Arrays.equals(r.getData(), data);
        report(name, ok, String.format("SW %04X, data %s", r.getSW(), short_(r.getData()))
                + (ok ? "" : ", mong đợi " + short_(data)));
    }

    static String short_(byte[] b) {
        String h = HexFormat.of().formatHex(b);
        return h.length() > 24 ? h.substring(0, 24) + "…(" + b.length + " byte)" : (h.isEmpty() ? "(rỗng)" : h);
    }

    static void report(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("  %-58s %s  %s%n", name, ok ? "ĐẠT" : "KHÔNG ĐẠT", detail);
    }
}
