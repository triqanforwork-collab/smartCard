package gym.desktop;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CSDL SQLite của phòng gym (file gym.db cạnh chỗ chạy app, tự tạo khi chạy lần đầu).
 * Lưu bản sao thông tin hội viên để tra cứu, khóa công khai RSA của từng thẻ, lịch sử giao dịch,
 * lượt check-in, gói tập, tài khoản Admin và nhật ký hoạt động.
 * Không lưu PIN hay khóa AES: những thứ đó chỉ nằm trên thẻ.
 */
public class Database implements AutoCloseable {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static final String TOPUP = "Nạp tiền", PAY = "Thanh toán", TRANSFER = "Chuyển số dư";
    public static final String ACTIVE = "Hoạt động", LOST = "Đã báo mất";

    public record GymPackage(int id, String name, int months, long price, boolean active) {
        @Override
        public String toString() {
            return name + " – " + price + " nghìn đồng";
        }
    }

    public record Transaction(long id, String maHV, String type, long amount, long balanceAfter, long txCount,
                              String note, boolean verified, String time) {
    }

    /** Một dòng trong danh sách hội viên: thông tin, trạng thái thẻ, thẻ thứ mấy, số lượt tập. */
    public record Member(MemberInfo info, String status, int cardNo, int checkins) {
    }

    /** Số dư và số giao dịch mà CSDL ghi nhận cho thẻ hiện tại của hội viên. */
    public record Expected(long balance, long txCount) {
    }

    public record LogEntry(String time, String admin, String maHV, String action, String detail, boolean warning) {
    }

    public record Stats(int members, int active, int expired, int expiringSoon, long topUpThisMonth,
                        int checkInsToday, int warningsToday) {
    }

    private final Connection conn;
    /** Admin đang đăng nhập, được ghi vào nhật ký cùng mỗi hoạt động. */
    private String currentAdmin;

    public Database(String file) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS members (
                        ma_hv      TEXT PRIMARY KEY,
                        ho_ten     TEXT NOT NULL,
                        ngay_sinh  TEXT,
                        sdt        TEXT,
                        goi_tap    TEXT,
                        han_goi    TEXT,
                        public_key BLOB NOT NULL,
                        created_at TEXT NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS packages (
                        id       INTEGER PRIMARY KEY,
                        ten      TEXT NOT NULL,
                        so_thang INTEGER NOT NULL,
                        gia      INTEGER NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        ma_hv        TEXT NOT NULL REFERENCES members(ma_hv),
                        loai         TEXT NOT NULL,
                        so_tien      INTEGER NOT NULL,
                        so_du_sau    INTEGER NOT NULL,
                        so_giao_dich INTEGER NOT NULL,
                        noi_dung     TEXT,
                        nonce        BLOB NOT NULL,
                        chu_ky       BLOB NOT NULL,
                        hop_le       INTEGER NOT NULL,
                        thoi_gian    TEXT NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS admins (
                        username   TEXT PRIMARY KEY,
                        salt       BLOB NOT NULL,
                        hash       BLOB NOT NULL,
                        iterations INTEGER NOT NULL,
                        created_at TEXT NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS lost_cards (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        ma_hv      TEXT NOT NULL REFERENCES members(ma_hv),
                        the_so     INTEGER NOT NULL,
                        public_key BLOB NOT NULL,
                        thoi_gian  TEXT NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS checkins (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        ma_hv     TEXT NOT NULL REFERENCES members(ma_hv),
                        the_so    INTEGER NOT NULL,
                        thoi_gian TEXT NOT NULL)""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        thoi_gian TEXT NOT NULL,
                        admin     TEXT,
                        ma_hv     TEXT,
                        hanh_dong TEXT NOT NULL,
                        chi_tiet  TEXT,
                        canh_bao  INTEGER NOT NULL)""");
            // Cột thêm sau: CSDL tạo từ bản cũ được bổ sung, dữ liệu giữ nguyên
            addColumnIfMissing("members", "the_so", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("members", "trang_thai", "TEXT NOT NULL DEFAULT '" + ACTIVE + "'");
            addColumnIfMissing("transactions", "the_so", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("packages", "dang_ban", "INTEGER NOT NULL DEFAULT 1");
            // Giá theo nghìn đồng, Admin sửa được ở màn Quản lý gói tập
            st.execute("""
                    INSERT OR IGNORE INTO packages (id, ten, so_thang, gia) VALUES
                        (1, 'Gói 1 tháng', 1, 300), (2, 'Gói 3 tháng', 3, 800),
                        (3, 'Gói 6 tháng', 6, 1500), (4, 'Gói 12 tháng', 12, 2800)""");
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equals(column)) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    // ==================== Nhật ký hoạt động ====================

    public void setCurrentAdmin(String admin) {
        currentAdmin = admin;
    }

    /** Ghi một dòng nhật ký. Không làm hỏng nghiệp vụ nếu ghi lỗi. */
    public void log(String maHV, String action, String detail, boolean warning) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO logs (thoi_gian, admin, ma_hv, hanh_dong, chi_tiet, canh_bao) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, now());
            ps.setString(2, currentAdmin);
            ps.setString(3, maHV);
            ps.setString(4, action);
            ps.setString(5, detail);
            ps.setInt(6, warning ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /** Nhật ký mới nhất trước; onlyWarnings thì chỉ lấy các dòng cảnh báo. */
    public List<LogEntry> logs(boolean onlyWarnings) throws SQLException {
        List<LogEntry> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM logs" + (onlyWarnings ? " WHERE canh_bao = 1" : "") + " ORDER BY id DESC LIMIT 500")) {
            while (rs.next()) {
                list.add(new LogEntry(rs.getString("thoi_gian"), rs.getString("admin"), rs.getString("ma_hv"),
                        rs.getString("hanh_dong"), rs.getString("chi_tiet"), rs.getInt("canh_bao") == 1));
            }
        }
        return list;
    }

    // ==================== Tài khoản Admin ====================
    // Mật khẩu không lưu dạng gốc: lưu PBKDF2-HMAC-SHA256(mật khẩu, salt ngẫu nhiên 16 byte, 100 000 vòng).

    static final int PBKDF2_ITERATIONS = 100_000;

    public boolean hasAdmin() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 FROM admins LIMIT 1")) {
            return rs.next();
        }
    }

    public void createAdmin(String username, char[] password) throws SQLException {
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO admins VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setBytes(2, salt);
            ps.setBytes(3, pbkdf2(password, salt, PBKDF2_ITERATIONS));
            ps.setInt(4, PBKDF2_ITERATIONS);
            ps.setString(5, now());
            ps.executeUpdate();
        }
    }

    /** Đúng tên đăng nhập và mật khẩu thì trả true. */
    public boolean checkAdmin(String username, char[] password) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT salt, hash, iterations FROM admins WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                byte[] hash = pbkdf2(password, rs.getBytes("salt"), rs.getInt("iterations"));
                return MessageDigest.isEqual(hash, rs.getBytes("hash")); // so sánh thời gian cố định
            }
        }
    }

    /** Đổi mật khẩu: kiểm tra mật khẩu cũ, lưu bản băm mới với salt mới. Sai mật khẩu cũ thì trả false. */
    public boolean changeAdminPassword(String username, char[] oldPassword, char[] newPassword) throws SQLException {
        if (!checkAdmin(username, oldPassword)) return false;
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE admins SET salt = ?, hash = ?, iterations = ? WHERE username = ?")) {
            ps.setBytes(1, salt);
            ps.setBytes(2, pbkdf2(newPassword, salt, PBKDF2_ITERATIONS));
            ps.setInt(3, PBKDF2_ITERATIONS);
            ps.setString(4, username);
            ps.executeUpdate();
        }
        return true;
    }

    private static byte[] newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== Hội viên ====================

    /** Lưu hội viên mới khi phát hành thẻ, kèm khóa công khai RSA của thẻ. */
    public void addMember(MemberInfo m, PublicKey cardKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO members (ma_hv, ho_ten, ngay_sinh, sdt, goi_tap, han_goi, public_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, m.maHV);
            ps.setString(2, m.hoTen);
            ps.setString(3, m.ngaySinh);
            ps.setString(4, m.sdt);
            ps.setString(5, m.goiTap);
            ps.setString(6, m.hanGoi);
            ps.setBytes(7, cardKey.getEncoded());
            ps.setString(8, now());
            ps.executeUpdate();
        }
    }

    /** Cập nhật bản sao thông tin sau khi sửa trên thẻ (khóa công khai giữ nguyên). */
    public void updateMember(MemberInfo m) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE members SET ho_ten = ?, ngay_sinh = ?, sdt = ?, goi_tap = ?, han_goi = ? WHERE ma_hv = ?")) {
            ps.setString(1, m.hoTen);
            ps.setString(2, m.ngaySinh);
            ps.setString(3, m.sdt);
            ps.setString(4, m.goiTap);
            ps.setString(5, m.hanGoi);
            ps.setString(6, m.maHV);
            ps.executeUpdate();
        }
    }

    public boolean memberExists(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM members WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Danh sách hội viên kèm trạng thái thẻ và số lượt tập; keyword tìm theo mã, tên hoặc số điện thoại. */
    public List<Member> members(String keyword) throws SQLException {
        String k = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT m.*, (SELECT COUNT(*) FROM checkins c WHERE c.ma_hv = m.ma_hv) AS luot
                FROM members m WHERE ma_hv LIKE ? OR ho_ten LIKE ? OR sdt LIKE ? ORDER BY ma_hv""")) {
            ps.setString(1, k);
            ps.setString(2, k);
            ps.setString(3, k);
            List<Member> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemberInfo m = new MemberInfo();
                    m.maHV = rs.getString("ma_hv");
                    m.hoTen = rs.getString("ho_ten");
                    m.ngaySinh = rs.getString("ngay_sinh");
                    m.sdt = rs.getString("sdt");
                    m.goiTap = rs.getString("goi_tap");
                    m.hanGoi = rs.getString("han_goi");
                    list.add(new Member(m, rs.getString("trang_thai"), rs.getInt("the_so"), rs.getInt("luot")));
                }
            }
            return list;
        }
    }

    public List<MemberInfo> findMembers(String keyword) throws SQLException {
        return members(keyword).stream().map(Member::info).toList();
    }

    /** Trạng thái thẻ hiện tại của hội viên (Hoạt động / Đã báo mất); null nếu không có hội viên. */
    public String memberStatus(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT trang_thai FROM members WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Khóa công khai RSA của thẻ hiện tại; null nếu chưa có hội viên này. */
    public PublicKey publicKey(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT public_key FROM members WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? toKey(rs.getBytes(1)) : null;
            }
        }
    }

    private static PublicKey toKey(byte[] encoded) throws SQLException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new SQLException("Khóa công khai trong CSDL bị hỏng", e);
        }
    }

    // ==================== Mất thẻ, cấp lại thẻ ====================

    /** Báo mất: lưu khóa công khai của thẻ đang dùng vào danh sách thẻ mất, chuyển trạng thái sang Đã báo mất. */
    public void reportLost(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO lost_cards (ma_hv, the_so, public_key, thoi_gian)
                SELECT ma_hv, the_so, public_key, ? FROM members WHERE ma_hv = ?""")) {
            ps.setString(1, now());
            ps.setString(2, maHV);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET trang_thai = ? WHERE ma_hv = ?")) {
            ps.setString(1, LOST);
            ps.setString(2, maHV);
            ps.executeUpdate();
        }
    }

    /** Khóa này có phải của một thẻ đã báo mất của hội viên không. */
    public boolean isLostCard(String maHV, PublicKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT public_key FROM lost_cards WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (Arrays.equals(rs.getBytes(1), key.getEncoded())) return true;
                }
            }
        }
        return false;
    }

    /** Cấp lại thẻ: thay khóa công khai bằng khóa của thẻ mới, tăng số thứ tự thẻ, trạng thái về Hoạt động. */
    public void reissue(MemberInfo m, PublicKey newKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE members SET public_key = ?, the_so = the_so + 1, trang_thai = ?,
                    ho_ten = ?, ngay_sinh = ?, sdt = ?, goi_tap = ?, han_goi = ? WHERE ma_hv = ?""")) {
            ps.setBytes(1, newKey.getEncoded());
            ps.setString(2, ACTIVE);
            ps.setString(3, m.hoTen);
            ps.setString(4, m.ngaySinh);
            ps.setString(5, m.sdt);
            ps.setString(6, m.goiTap);
            ps.setString(7, m.hanGoi);
            ps.setString(8, m.maHV);
            ps.executeUpdate();
        }
    }

    // ==================== Gói tập ====================

    /** Các gói đang bán (hiện ở màn Gia hạn). */
    public List<GymPackage> packages() throws SQLException {
        return queryPackages("SELECT * FROM packages WHERE dang_ban = 1 ORDER BY so_thang, id");
    }

    /** Mọi gói, kể cả đã ngừng bán (màn Quản lý gói tập). */
    public List<GymPackage> allPackages() throws SQLException {
        return queryPackages("SELECT * FROM packages ORDER BY so_thang, id");
    }

    private List<GymPackage> queryPackages(String sql) throws SQLException {
        List<GymPackage> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new GymPackage(rs.getInt("id"), rs.getString("ten"), rs.getInt("so_thang"),
                        rs.getLong("gia"), rs.getInt("dang_ban") == 1));
            }
        }
        return list;
    }

    public void addPackage(String name, int months, long price) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO packages (ten, so_thang, gia, dang_ban) VALUES (?, ?, ?, 1)")) {
            ps.setString(1, name);
            ps.setInt(2, months);
            ps.setLong(3, price);
            ps.executeUpdate();
        }
    }

    public void updatePackage(int id, String name, int months, long price) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE packages SET ten = ?, so_thang = ?, gia = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, months);
            ps.setLong(3, price);
            ps.setInt(4, id);
            ps.executeUpdate();
        }
    }

    public void setPackageActive(int id, boolean active) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE packages SET dang_ban = ? WHERE id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    // ==================== Giao dịch ====================

    /** Ghi giao dịch của thẻ hiện tại của hội viên. */
    public void addTransaction(String maHV, String type, long amount, long balanceAfter, long txCount,
                               String note, byte[] nonce, byte[] signature, boolean verified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO transactions (ma_hv, loai, so_tien, so_du_sau, so_giao_dich, noi_dung, nonce, chu_ky, hop_le,
                    thoi_gian, the_so)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT the_so FROM members WHERE ma_hv = ?))""")) {
            ps.setString(1, maHV);
            ps.setString(2, type);
            ps.setLong(3, amount);
            ps.setLong(4, balanceAfter);
            ps.setLong(5, txCount);
            ps.setString(6, note);
            ps.setBytes(7, nonce);
            ps.setBytes(8, signature);
            ps.setInt(9, verified ? 1 : 0);
            ps.setString(10, now());
            ps.setString(11, maHV);
            ps.executeUpdate();
        }
    }

    /** Lịch sử giao dịch mới nhất trước; maHV rỗng thì lấy của mọi hội viên. */
    public List<Transaction> transactions(String maHV) throws SQLException {
        boolean all = maHV == null || maHV.isBlank();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM transactions" + (all ? "" : " WHERE ma_hv = ?") + " ORDER BY id DESC")) {
            if (!all) ps.setString(1, maHV);
            List<Transaction> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Transaction(rs.getLong("id"), rs.getString("ma_hv"), rs.getString("loai"),
                            rs.getLong("so_tien"), rs.getLong("so_du_sau"), rs.getLong("so_giao_dich"),
                            rs.getString("noi_dung"), rs.getInt("hop_le") == 1, rs.getString("thoi_gian")));
                }
            }
            return list;
        }
    }

    /** Số dư và số giao dịch CSDL ghi nhận cho thẻ hiện tại (chưa có giao dịch thì 0, 0). Dùng để đối chiếu với thẻ. */
    public Expected expected(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT so_du_sau, so_giao_dich FROM transactions
                WHERE ma_hv = ? AND the_so = (SELECT the_so FROM members WHERE ma_hv = ?)
                ORDER BY so_giao_dich DESC LIMIT 1""")) {
            ps.setString(1, maHV);
            ps.setString(2, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Expected(rs.getLong(1), rs.getLong(2)) : new Expected(0, 0);
            }
        }
    }

    /** Số giao dịch lớn nhất đã ghi của thẻ hiện tại, để phát hiện thẻ gửi lại giao dịch cũ. */
    public long lastTxCount(String maHV) throws SQLException {
        return expected(maHV).txCount();
    }

    // ==================== Check-in ====================

    public void addCheckIn(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO checkins (ma_hv, the_so, thoi_gian) VALUES (?, (SELECT the_so FROM members WHERE ma_hv = ?), ?)")) {
            ps.setString(1, maHV);
            ps.setString(2, maHV);
            ps.setString(3, now());
            ps.executeUpdate();
        }
    }

    public int checkInCount(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM checkins WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== Thống kê ====================

    /** Hạn gói dạng dd/MM/yyyy; trống hoặc sai định dạng thì null. */
    public static LocalDate expiry(String hanGoi) {
        try {
            return LocalDate.parse(hanGoi, DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Hội viên còn hạn nhưng hết hạn trong vòng days ngày tới (tính từ today), hạn gần nhất trước. */
    public List<MemberInfo> expiringWithin(int days, LocalDate today) throws SQLException {
        List<MemberInfo> list = new ArrayList<>();
        for (MemberInfo m : findMembers("")) {
            LocalDate e = expiry(m.hanGoi);
            if (e != null && !e.isBefore(today) && !e.isAfter(today.plusDays(days))) list.add(m);
        }
        list.sort((a, b) -> expiry(a.hanGoi).compareTo(expiry(b.hanGoi)));
        return list;
    }

    public Stats stats(LocalDate today) throws SQLException {
        int total = 0, active = 0;
        for (MemberInfo m : findMembers("")) {
            total++;
            LocalDate e = expiry(m.hanGoi);
            if (e != null && !e.isBefore(today)) active++;
        }
        String day = today.format(DATE);                 // dd/MM/yyyy
        String month = "__/" + day.substring(3) + "%";   // __/MM/yyyy%
        return new Stats(total, active, total - active, expiringWithin(7, today).size(),
                longQuery("SELECT COALESCE(SUM(so_tien), 0) FROM transactions WHERE loai = ? AND thoi_gian LIKE ?", TOPUP, month),
                (int) longQuery("SELECT COUNT(*) FROM checkins WHERE thoi_gian LIKE ?", day + "%"),
                (int) longQuery("SELECT COUNT(*) FROM logs WHERE canh_bao = 1 AND thoi_gian LIKE ?", day + "%"));
    }

    private long longQuery(String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
