import gym.desktop.Database;
import gym.desktop.MemberInfo;

import java.io.File;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Kiểm tra lớp Database trên SQLite hoặc MySQL, không cần thẻ (khóa RSA sinh bằng Java).
 * SQLite:  java -cp "<classpath>" tools/DbCheck.java
 * MySQL:   java -cp "<classpath>" tools/DbCheck.java jdbc:mysql://localhost:3306/gym_test root <mật khẩu>
 * Với MySQL, tên CSDL phải chứa "test" vì chương trình xóa sạch các bảng trước khi chạy.
 */
public class DbCheck {
    static int pass, fail;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  [ĐẠT] " : "  [KHÔNG ĐẠT] ") + name);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) throws Exception {
        Database db;
        if (a.length >= 2) {
            if (!a[0].contains("test")) throw new IllegalArgumentException("Tên CSDL MySQL phải chứa \"test\"");
            try (var c = DriverManager.getConnection(a[0], a[1], a.length > 2 ? a[2] : "");
                 Statement st = c.createStatement()) {
                for (String t : new String[]{"logs", "checkins", "lost_cards", "transactions", "admins", "packages", "members"})
                    st.execute("DROP TABLE IF EXISTS " + t);
            }
            db = new Database(a[0], a[1], a.length > 2 ? a[2] : "");
        } else {
            File f = File.createTempFile("gym-dbcheck", ".db");
            f.deleteOnExit();
            db = new Database(f.getPath());
        }
        try (db) {
            System.out.println("CSDL: " + (a.length >= 2 ? a[0] : "SQLite (file tạm)"));
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(1024);
            PublicKey k1 = g.generateKeyPair().getPublic(), k2 = g.generateKeyPair().getPublic();
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

            check("4 gói tập mặc định", db.packages().size() == 4);
            MemberInfo m = new MemberInfo();
            m.maHV = "HV900"; m.hoTen = "Nguyễn Thị Ánh Dương"; m.ngaySinh = "01/01/2000"; m.sdt = "0912345678";
            m.goiTap = "Gói 1 tháng"; m.hanGoi = LocalDate.now().plusDays(3).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            db.addMember(m, k1);
            check("Thêm hội viên, tên tiếng Việt có dấu đọc lại đúng",
                    db.findMembers("Ánh").size() == 1 && db.findMembers("HV900").get(0).hoTen.equals(m.hoTen));
            check("Khóa công khai đọc lại khớp", db.publicKey("HV900").equals(k1));
            check("Trạng thái mặc định Hoạt động, thẻ số 1",
                    Database.ACTIVE.equals(db.memberStatus("HV900")) && db.members("HV900").get(0).cardNo() == 1);

            db.addTransaction("HV900", Database.TOPUP, 500, 500, 1, "nạp", new byte[16], new byte[128], true);
            db.addTransaction("HV900", Database.PAY, 300, 200, 2, "gia hạn", new byte[16], new byte[128], true);
            check("Giao dịch: số dư 200, giao dịch số 2",
                    db.expected("HV900").balance() == 200 && db.expected("HV900").txCount() == 2 && db.transactions("HV900").size() == 2);
            boolean fk;
            try {
                db.addTransaction("HV999", Database.TOPUP, 1, 1, 1, "", new byte[16], new byte[128], true);
                fk = false;
            } catch (java.sql.SQLException e) {
                fk = true;
            }
            check("Khóa ngoại chặn giao dịch của hội viên không tồn tại", fk);

            db.addCheckIn("HV900");
            check("Check-in ghi 1 lượt", db.checkInCount("HV900") == 1);
            db.setCurrentAdmin("admin");
            db.log("HV900", "Số dư lệch", "thử", true);
            check("Nhật ký: 1 cảnh báo, ghi kèm Admin",
                    db.logs(true).size() == 1 && "admin".equals(db.logs(true).get(0).admin()));

            db.createAdmin("admin", "matkhau123".toCharArray());
            check("Admin: đúng mật khẩu thì vào, sai thì không",
                    db.hasAdmin() && db.checkAdmin("admin", "matkhau123".toCharArray()) && !db.checkAdmin("admin", "x".toCharArray()));

            db.addPackage("Gói 2 tháng", 2, 550);
            int id = db.allPackages().stream().filter(p -> p.months() == 2).findFirst().orElseThrow().id();
            db.setPackageActive(id, false);
            check("Thêm gói (id tự tăng), ngừng bán thì ẩn khỏi danh sách đang bán",
                    db.allPackages().size() == 5 && db.packages().size() == 4);

            Database.Stats s = db.stats(LocalDate.now());
            check("Thống kê: 1 hội viên, 1 check-in hôm nay, nạp tháng này 500 (" + today + ")",
                    s.members() == 1 && s.checkInsToday() == 1 && s.topUpThisMonth() == 500 && s.warningsToday() == 1);
            check("Sắp hết hạn trong 7 ngày: HV900", db.expiringWithin(7, LocalDate.now()).size() == 1);

            db.reportLost("HV900");
            check("Báo mất: trạng thái Đã báo mất, thẻ cũ nằm trong danh sách mất",
                    Database.LOST.equals(db.memberStatus("HV900")) && db.isLostCard("HV900", k1));
            db.reissue(m, k2);
            check("Cấp lại: thẻ số 2, khóa mới, số dư thẻ mới bắt đầu từ 0",
                    db.members("HV900").get(0).cardNo() == 2 && db.publicKey("HV900").equals(k2)
                            && !db.isLostCard("HV900", k2) && db.expected("HV900").balance() == 0);
        }
        System.out.printf("%nKẾT QUẢ: %d đạt, %d không đạt%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
