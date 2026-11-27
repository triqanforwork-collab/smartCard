import gym.desktop.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Chạy kịch bản demo (docs/KICH_BAN_DEMO.md) qua các lớp dịch vụ của phần mềm, kèm các trường hợp lỗi.
 * Cần thẻ trắng (bấm Run trong JCIDE) và đã build desktop. Lệnh chạy: xem cuối docs/KICH_BAN_DEMO.md.
 * Dùng file CSDL tạm, không đụng tới gym.db.
 */
public class ScenarioTest {
    static int pass, fail;
    static CardService card = new CardService();
    static String reader;

    interface Op { void run() throws Exception; }

    /** CSDL của lượt chạy; lượt 2 (reissue) dùng lại CSDL của lượt 1. */
    static final File DB_FILE = new File(System.getProperty("java.io.tmpdir"), "gym-scenario.db");

    public static void main(String[] args) throws Exception {
        boolean part2 = args.length > 0 && args[0].equals("reissue");
        File dbFile = DB_FILE;
        if (!part2) dbFile.delete();
        reader = CardService.readers().stream().filter(r -> r.contains("Contact Reader")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Không thấy đầu đọc JCIDE. Bấm Run trong JCIDE trước."));
        card.connect(reader);
        if (card.isInitialized()) {
            System.out.println("Thẻ không còn trắng. Bấm Run trong JCIDE rồi chạy lại.");
            return;
        }

        if (part2) {
            reissuePart(dbFile);
            return;
        }

        try (Database db = new Database(dbFile.getPath())) {
            MemberService members = new MemberService(card, db);
            WalletService wallet = new WalletService(card, db);
            DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            section("K01. Bước 1–3: kết nối thẻ trắng, kiểm tra dữ liệu, khởi tạo thẻ");
            MemberInfo m = new MemberInfo();
            m.maHV = "HV100"; m.hoTen = "Lê Văn An"; m.ngaySinh = "15/08/2003"; m.sdt = "12345";
            expectError("Số điện thoại 12345 bị chặn trước khi gửi xuống thẻ", m::validate, "Số điện thoại");
            m.sdt = "0912345678";
            m.validate();
            byte[] photo = MainFrame.compressPhoto(ImageIO.read(new File("docs/anh-mau.jpg")));
            check("Ảnh mẫu " + new File("docs/anh-mau.jpg").length() + " byte được nén còn " + photo.length + " byte", photo.length <= CardService.MAX_PHOTO);
            MemberInfo m0 = m;
            expectError("Phòng gym chưa đặt PIN Admin thì chưa phát hành thẻ được",
                    () -> members.issueCard("1234", "87654321", m0, photo), "Chưa đặt PIN Admin");
            db.setAdminPin("87654321");
            expectError("PIN Admin nhập sai bị phần mềm chặn, chưa gửi lệnh INIT xuống thẻ",
                    () -> members.issueCard("1234", "11112222", m0, photo), "PIN Admin không đúng");
            members.issueCard("1234", "87654321", m, photo);
            check("Thẻ đã khởi tạo, CSDL có HV100 và khóa công khai của thẻ",
                    card.isInitialized() && db.publicKey("HV100").equals(card.getPublicKey()));
            check("Ảnh và thông tin đọc lại từ thẻ đúng", Arrays.equals(card.getPhoto(), photo) && card.getInfo().hoTen.equals("Lê Văn An"));
            check("Số dư ban đầu 0", wallet.balance().balance() == 0);

            section("K01. Bước 4–7: nạp tiền, gia hạn gói, không đủ tiền, lịch sử");
            CardService.TxResult t = wallet.topUp("HV100", 2000, "87654321", null);
            check("Nạp 2000: số dư 2000, ghi CSDL 1 giao dịch hợp lệ", t.balance() == 2000 && db.transactions("HV100").size() == 1);
            Database.GymPackage p3 = pkg(db, 3), p12 = pkg(db, 12);
            m = wallet.payPackage(card.getInfo(), p3);
            String expect = LocalDate.now().plusMonths(3).format(dmy);
            check("Gói 3 tháng: số dư 1200, hạn mới " + m.hanGoi + " (mong đợi " + expect + ")",
                    wallet.balance().balance() == 1200 && m.hanGoi.equals(expect) && card.getInfo().hanGoi.equals(expect));
            MemberInfo cur = m;
            expectSw("Gói 12 tháng (2800) khi còn 1200 bị từ chối", () -> wallet.payPackage(cur, p12), 0x6910);
            check("Sau khi bị từ chối: số dư vẫn 1200, CSDL vẫn 2 giao dịch",
                    wallet.balance().balance() == 1200 && db.transactions("HV100").size() == 2);
            check("Lịch sử: 2 giao dịch đều có chữ ký hợp lệ, mới nhất là thanh toán",
                    db.transactions("HV100").stream().allMatch(Database.Transaction::verified)
                            && db.transactions("HV100").get(0).type().equals(Database.PAY));

            section("D05, G06. Đối chiếu số dư, check-in / check-out");
            check("Đối chiếu: số dư thẻ khớp CSDL (1200, giao dịch số 2)", members.reconcile("HV100") == null);
            LocalDateTime t0 = LocalDateTime.now();
            reconnect();   // quẹt thẻ: chưa nhập PIN
            MemberService.Swipe in1 = members.swipe(t0);
            check("Quẹt thẻ (không PIN, chữ ký RSA + CSDL) khi đang ở ngoài: check-in, ghi 1 lượt, còn " + in1.daysLeft() + " ngày",
                    in1.checkIn() && db.checkInCount("HV100") == 1 && in1.member().maHV.equals("HV100")
                            && in1.daysLeft() >= 89 && in1.daysLeft() <= 92);
            expectError("Quẹt lại ngay sau 30 giây bị bỏ qua (lỡ tay quẹt 2 lần)", () -> members.swipe(t0.plusSeconds(30)), "vừa check-in");
            MemberService.Swipe out1 = members.swipe(t0.plusMinutes(45));
            check("Quẹt khi đang trong phòng: check-out, đã tập 45 phút", !out1.checkIn() && out1.stayed().toMinutes() == 45
                    && db.lastVisit("HV100").out() != null);
            expectError("Quẹt ngay sau check-out bị bỏ qua", () -> members.swipe(t0.plusMinutes(45).plusSeconds(20)), "vừa check-out");
            check("Đã check-out thì check-in lại được ngay, không phải chờ 2 giờ",
                    members.swipe(t0.plusMinutes(50)).checkIn() && db.checkInCount("HV100") == 2);
            expectError("Quên check-out rồi sang ngày khác: lượt cũ tự đóng; gói đã hết hạn (giả lập 2 năm sau) thì từ chối",
                    () -> members.swipe(t0.plusYears(2)), "hết hạn");
            check("Check-in bị từ chối không ghi lượt; nhật ký có Quên check-out", db.checkInCount("HV100") == 2
                    && db.lastVisit("HV100").closed()
                    && db.logs(false).stream().anyMatch(l -> l.action().equals("Quên check-out")));

            section("K01. Bước 8–11: đổi PIN, khóa thẻ, mở khóa, quên PIN");
            card.changePin("1234", "5678");
            reconnect();
            expectSw("Đăng nhập PIN cũ lần 1", () -> members.login("1234"), 0x63C2);
            expectSw("Đăng nhập PIN cũ lần 2", () -> members.login("1234"), 0x63C1);
            expectSw("Đăng nhập PIN cũ lần 3: khóa thẻ", () -> members.login("1234"), 0x6983);
            check("GET_STATUS báo bị khóa", card.getStatus().blocked());
            expectSw("PIN đúng (5678) cũng không vào được khi thẻ khóa", () -> members.login("5678"), 0x6983);
            expectSw("Mở khóa bằng PIN Admin sai", () -> card.unblock("11112222"), 0x63C2);
            card.unblock("87654321");
            check("Mở khóa xong đăng nhập 5678 được", members.login("5678").maHV.equals("HV100"));
            reconnect();
            card.resetPin("87654321", "2468");
            MemberInfo after = members.login("2468");
            check("Đặt lại PIN 2468: thông tin, ảnh, số dư 1200 còn nguyên",
                    after.hanGoi.equals(expect) && Arrays.equals(card.getPhoto(), photo) && wallet.balance().balance() == 1200);

            section("K01. Bước 12: danh sách hội viên");
            check("Tìm \"An\" thấy HV100, Gói 3 tháng", db.findMembers("An").size() == 1
                    && db.findMembers("An").get(0).goiTap.equals("Gói 3 tháng"));

            section("K02. Thẻ giả, thẻ chưa đăng ký, giao dịch bị gửi lại");
            byte[] realKey = swapKey(dbFile.getPath(), null);
            reconnect();
            expectError("Thẻ có khóa RSA khác khóa đã đăng ký bị từ chối khi đăng nhập", () -> members.login("2468"), "không hợp lệ");
            reconnect();
            expectError("Thẻ có khóa RSA chưa đăng ký bị từ chối khi check-in",
                    () -> members.swipe(LocalDateTime.now().plusHours(3)), "chưa được đăng ký");
            reconnect();
            card.verifyPin("2468");
            long before = db.transactions("HV100").size();
            expectError("Giao dịch có chữ ký không khớp không được ghi CSDL", () -> wallet.topUp("HV100", 100, "87654321", null), "Chữ ký");
            check("CSDL không thêm giao dịch nào", db.transactions("HV100").size() == before);
            swapKey(dbFile.getPath(), realKey);
            try (Database empty = new Database(File.createTempFile("gym-empty", ".db").getPath())) {
                reconnect();
                expectError("Thẻ chưa có trong CSDL phòng gym bị từ chối", () -> new MemberService(card, empty).login("2468"), "chưa được đăng ký");
            }
            reconnect();
            members.login("2468");
            String diff = members.reconcile("HV100");
            check("D05: thẻ bị cộng 100 mà CSDL không ghi nhận nên đối chiếu báo lệch", diff != null && diff.contains("khác với CSDL"));
            db.addTransaction("HV100", Database.TOPUP, 1, 1200, 99, "giả lập giao dịch số 99 đã ghi", new byte[16], new byte[128], true);
            expectError("Số thứ tự giao dịch không lớn hơn số đã ghi bị coi là gửi lại", () -> wallet.topUp("HV100", 100, "87654321", null), "gửi lại");

            section("G04, A09. Tài khoản Admin, nạp tiền đòi PIN Admin");
            check("CSDL mới chưa có tài khoản Admin", !db.hasAdmin());
            db.createAdmin("admin", "matkhau123".toCharArray());
            check("Tạo tài khoản admin, đăng nhập đúng mật khẩu được", db.hasAdmin() && db.checkAdmin("admin", "matkhau123".toCharArray()));
            check("Sai mật khẩu hoặc sai tên đăng nhập bị từ chối",
                    !db.checkAdmin("admin", "matkhau124".toCharArray()) && !db.checkAdmin("root", "matkhau123".toCharArray()));
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath())) {
                ResultSet rs = c.createStatement().executeQuery("SELECT hash, length(salt) FROM admins WHERE username = 'admin'");
                rs.next();
                check("Mật khẩu lưu dạng băm PBKDF2 32 byte có salt 16 byte, không lưu mật khẩu gốc",
                        rs.getBytes(1).length == 32 && rs.getInt(2) == 16 && !new String(rs.getBytes(1)).contains("matkhau123"));
            }
            long bal = wallet.balance().balance();
            expectError("Nạp tiền với PIN Admin sai bị phần mềm chặn trước khi gửi xuống thẻ",
                    () -> wallet.topUp("HV100", 500, "11112222", null), "PIN Admin không đúng");
            check("Số dư không đổi sau khi nạp tiền bị từ chối", wallet.balance().balance() == bal);

            section("K02. Dữ liệu sai, rút thẻ");
            expectError("Nạp 0 đồng bị chặn", () -> wallet.topUp("HV100", 0, "87654321", null), "không hợp lệ");
            expectError("Ảnh lớn hơn 10240 byte bị chặn", () -> card.setPhoto(new byte[10241]), "quá lớn");
            MemberInfo longName = card.getInfo();
            longName.hoTen = "Nguyễn".repeat(40);
            expectError("Thông tin quá 200 byte bị chặn", longName::validate, "quá dài");
            reconnect();
            expectSw("Rút thẻ cắm lại: đọc thông tin phải nhập PIN lại", () -> card.getInfo(), 0x6982);
            expectSw("Rút thẻ cắm lại: xem số dư phải nhập PIN lại", () -> card.getBalance(), 0x6982);
            expectSw("Khởi tạo lại thẻ đã dùng bị từ chối", () -> card.init("1111", "22222222", new MemberInfo()), 0x6985);

            section("D06. Nhật ký hoạt động");
            java.util.List<String> warn = db.logs(true).stream().map(Database.LogEntry::action).toList();
            java.util.List<String> all = db.logs(false).stream().map(Database.LogEntry::action).toList();
            check("Cảnh báo có: phát hiện thẻ giả, số dư lệch, giao dịch gửi lại, nạp tiền bị thẻ từ chối",
                    warn.contains("Phát hiện thẻ giả") && warn.contains("Số dư lệch")
                            && warn.contains("Nạp tiền: giao dịch gửi lại") && warn.contains("Nạp tiền bị thẻ từ chối"));
            check("Nhật ký có: phát hành thẻ, nạp tiền, thanh toán, check-in, check-in bị từ chối",
                    all.contains("Phát hành thẻ") && all.contains("Nạp tiền") && all.contains("Thanh toán")
                            && all.contains("Check-in") && all.contains("Check-in bị từ chối"));

            section("G07. Thống kê");
            Database.Stats st = db.stats(LocalDate.now());
            check(String.format("Thống kê: %d hội viên, %d còn hạn, %d check-in hôm nay, nạp tháng này %d, %d cảnh báo hôm nay",
                            st.members(), st.active(), st.checkInsToday(), st.topUpThisMonth(), st.warningsToday()),
                    st.members() == 1 && st.active() == 1 && st.noPackage() == 0 && st.lostCards() + st.brokenCards() == 0 && st.checkInsToday() == 2 && st.inside() == 0 && st.topUpThisMonth() >= 2000 && st.warningsToday() >= 4);
            check("Sắp hết hạn: không ai trong 7 ngày, HV100 nằm trong 100 ngày",
                    db.expiringWithin(7, LocalDate.now()).isEmpty() && db.expiringWithin(100, LocalDate.now()).size() == 1);

            section("G08, G09. Quản lý gói tập, đổi mật khẩu Admin");
            db.addPackage("Gói 2 tháng", 2, 550);
            Database.GymPackage p2 = pkg(db, 2);
            check("Thêm Gói 2 tháng giá 550: hiện trong danh sách đang bán", p2.price() == 550 && p2.active());
            db.updatePackage(p2.id(), "Gói 2 tháng", 2, 600);
            check("Sửa giá thành 600", pkg(db, 2).price() == 600);
            db.setPackageActive(p2.id(), false);
            check("Ngừng bán: không hiện ở màn Gia hạn, vẫn còn trong Quản lý gói",
                    db.packages().stream().noneMatch(x -> x.id() == p2.id()) && db.allPackages().stream().anyMatch(x -> x.id() == p2.id()));
            check("Đổi mật khẩu với mật khẩu cũ sai bị từ chối", !db.changeAdminPassword("admin", "sai".toCharArray(), "moi123456".toCharArray()));
            check("Đổi mật khẩu đúng: mật khẩu mới đăng nhập được, mật khẩu cũ thì không",
                    db.changeAdminPassword("admin", "matkhau123".toCharArray(), "moi123456".toCharArray())
                            && db.checkAdmin("admin", "moi123456".toCharArray()) && !db.checkAdmin("admin", "matkhau123".toCharArray()));

            section("A10. Đổi PIN Admin chung, thẻ cũ được cập nhật khi Admin thao tác (lệnh CHANGE_ADMIN_PIN)");
            check("Đổi PIN Admin: phiên bản 2, thẻ HV100 còn phiên bản 1",
                    db.setAdminPin("13572468") == 2 && db.memberAdminPinVersion("HV100") == 1);
            reconnect();
            expectError("Đặt lại PIN bằng PIN Admin cũ bị chặn (không còn là PIN hiện tại)",
                    () -> members.resetPin("87654321", "2468", q -> null), "PIN Admin không đúng");
            expectError("Thẻ còn PIN Admin cũ, không nhập PIN cũ thì không làm được",
                    () -> members.resetPin("13572468", "2468", q -> null), "PIN Admin cũ");
            expectError("Nhập sai PIN Admin cũ bị chặn trước khi gửi xuống thẻ",
                    () -> members.resetPin("13572468", "2468", q -> "11112222"), "cũ không đúng");
            members.resetPin("13572468", "2468", q -> "87654321");
            check("Nhập PIN cũ một lần: thẻ đổi sang PIN Admin mới rồi đặt lại PIN, CSDL ghi thẻ ở phiên bản 2",
                    db.memberAdminPinVersion("HV100") == 2);
            reconnect();
            check("Dữ liệu thẻ còn nguyên, đăng nhập bằng PIN hội viên được", members.login("2468").maHV.equals("HV100"));
            expectSw("Thẻ không còn nhận PIN Admin cũ", () -> card.unblock("87654321"), 0x63C2);
            card.unblock("13572468");
            check("Thẻ nhận PIN Admin mới", true);

            section("D07 (lượt 1). Báo mất thẻ");
            reconnect();
            members.login("2468");
            db.reportLost("HV100");
            check("Báo mất: trạng thái Đã báo mất", Database.LOST.equals(db.memberStatus("HV100")));
            reconnect();
            expectError("Thẻ đã báo mất bị từ chối kể cả khi nhập đúng PIN", () -> members.login("2468"), "báo mất");
            reconnect();
            expectError("Thẻ đã báo mất không check-in được", () -> members.swipe(LocalDateTime.now().plusHours(3)), "báo mất");
            check("Nhật ký cảnh báo có: dùng thẻ đã báo mất",
                    db.logs(true).stream().anyMatch(l -> l.action().equals("Dùng thẻ đã báo mất")));
        }
        card.disconnect();
        System.out.println("\nLượt 2 (cấp lại thẻ): bấm Run lại trong JCIDE để có thẻ trắng mới, rồi chạy lại lệnh này kèm tham số reissue.");
        System.out.printf("%nKẾT QUẢ: %d đạt, %d không đạt%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    /** Lượt 2: cấp lại thẻ cho HV100 (đã báo mất ở lượt 1) bằng một thẻ trắng mới. */
    static void reissuePart(File dbFile) throws Exception {
        try (Database db = new Database(dbFile.getPath())) {
            MemberService members = new MemberService(card, db);
            section("D07 (lượt 2). Cấp lại thẻ, chuyển số dư");
            check("CSDL của lượt 1: HV100 đang ở trạng thái Đã báo mất", Database.LOST.equals(db.memberStatus("HV100")));
            long old = db.expected("HV100").balance();
            MemberInfo info = db.findMembers("HV100").get(0);
            members.reissueCard("1357", "13572468", info, null);
            check("Cấp lại thẻ: trạng thái Hoạt động, thẻ số 2, khóa công khai mới khớp thẻ mới",
                    Database.ACTIVE.equals(db.memberStatus("HV100")) && db.members("HV100").get(0).cardNo() == 2
                            && db.publicKey("HV100").equals(card.getPublicKey()));
            check("Số dư thẻ mới = số dư CSDL ghi nhận cho thẻ cũ (" + old + ")", card.getBalance().balance() == old && old > 0);
            Database.Transaction last = db.transactions("HV100").get(0);
            check("Giao dịch \"Chuyển số dư\" có chữ ký hợp lệ được ghi", last.type().equals(Database.TRANSFER) && last.verified() && last.amount() == old);
            reconnect();
            check("Thẻ mới đăng nhập được bằng PIN mới", members.login("1357").maHV.equals("HV100"));
            check("Đối chiếu số dư thẻ mới khớp CSDL", members.reconcile("HV100") == null);
            MemberService.Swipe sw = members.swipe(LocalDateTime.now().plusHours(3));
            check("Thẻ mới check-in được", sw.checkIn() && sw.member().maHV.equals("HV100"));
            expectError("Cấp lại thẻ cho hội viên chưa báo mất bị từ chối", () -> members.reissueCard("1357", "13572468", info, null), "đã báo mất");
            check("Nhật ký có: cấp lại thẻ, chuyển số dư",
                    db.logs(false).stream().anyMatch(l -> l.action().equals("Cấp lại thẻ"))
                            && db.logs(false).stream().anyMatch(l -> l.action().equals(Database.TRANSFER)));
        }
        card.disconnect();
        System.out.printf("%nKẾT QUẢ: %d đạt, %d không đạt%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static Database.GymPackage pkg(Database db, int months) throws Exception {
        return db.packages().stream().filter(p -> p.months() == months).findFirst().orElseThrow();
    }

    static void reconnect() throws Exception {
        card.disconnect();
        card.connect(reader);
    }

    /** Thay khóa công khai của HV100 trong CSDL bằng khóa khác (giả lập thẻ giả); key = null thì sinh khóa ngẫu nhiên. Trả khóa cũ. */
    static byte[] swapKey(String dbFile, byte[] key) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            ResultSet rs = c.createStatement().executeQuery("SELECT public_key FROM members WHERE ma_hv = 'HV100'");
            rs.next();
            byte[] old = rs.getBytes(1);
            if (key == null) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(1024);
                key = g.generateKeyPair().getPublic().getEncoded();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE members SET public_key = ? WHERE ma_hv = 'HV100'")) {
                ps.setBytes(1, key);
                ps.executeUpdate();
            }
            return old;
        }
    }

    static void section(String s) { System.out.println("\n== " + s); }

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.printf("  %-90s %s%n", name, ok ? "ĐẠT" : "KHÔNG ĐẠT");
    }

    static void expectSw(String name, Op op, int sw) {
        int got = 0x9000;
        try {
            op.run();
        } catch (CardService.CardError e) {
            got = e.sw;
        } catch (Exception e) {
            got = -1;
        }
        check(name + String.format(" (SW %04X)", got & 0xFFFF), got == sw);
    }

    static void expectError(String name, Op op, String text) {
        String msg = null;
        try {
            op.run();
        } catch (Exception e) {
            msg = e.getMessage();
        }
        check(name + (msg == null ? " (không báo lỗi)" : ": \"" + msg + "\""), msg != null && msg.contains(text));
    }
}
