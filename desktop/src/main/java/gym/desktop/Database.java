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
 * CSDL của phòng gym: MySQL nếu có file db.properties cạnh chỗ chạy app, không thì SQLite (file gym.db).
 * Bảng được tự tạo khi chạy lần đầu.
 * Lưu bản sao thông tin hội viên để tra cứu, khóa công khai RSA của từng thẻ, lịch sử giao dịch,
 * lượt check-in, gói tập, tài khoản Admin và nhật ký hoạt động.
 * Không lưu PIN hay khóa AES: những thứ đó chỉ nằm trên thẻ.
 */
public class Database implements AutoCloseable {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static final String TOPUP = "Nạp tiền", PAY = "Thanh toán", TRANSFER = "Chuyển số dư";
    public static final String ACTIVE = "Hoạt động", LOST = "Đã báo mất", BROKEN = "Thẻ hỏng";

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

    /**
     * Số liệu màn Quản trị. Hai nhóm độc lập: gói tập (còn hạn + hết hạn + chưa có gói = tổng hội viên)
     * và thẻ chờ cấp lại (báo mất, thẻ hỏng: tự hết khi Admin cấp thẻ mới vì trạng thái về Hoạt động).
     */
    public record Stats(int members, int active, int expired, int noPackage, int lostCards, int brokenCards,
                        int expiringSoon, long topUpThisMonth,
                        int checkInsToday, int warningsToday) {
    }

    private final Connection conn;
    /** Admin đang đăng nhập, được ghi vào nhật ký cùng mỗi hoạt động. */
    private String currentAdmin;

    /** SQLite: một file, không cần cài server (dùng khi chạy test, hoặc khi không có db.properties). */
    public Database(String file) throws SQLException {
        this(DriverManager.getConnection("jdbc:sqlite:" + file));
    }

    /** MySQL, ví dụ url = jdbc:mysql://localhost:3306/gym (CSDL gym phải được tạo trước, utf8mb4). */
    public Database(String url, String user, String password) throws SQLException {
        this(DriverManager.getConnection(url, user, password));
    }

    /**
     * Mở CSDL theo file db.properties (url, user, password) nếu có, không thì SQLite gym.db.
     * Mật khẩu MySQL chỉ nằm trong db.properties, file này không đưa lên git.
     */
    public static Database open() throws SQLException, java.io.IOException {
        java.nio.file.Path cfg = java.nio.file.Path.of("db.properties");
        if (!java.nio.file.Files.exists(cfg)) return new Database("gym.db");
        java.util.Properties p = new java.util.Properties();
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(cfg)) {
            p.load(r);
        }
        return new Database(p.getProperty("url"), p.getProperty("user"), p.getProperty("password", ""));
    }

    private final boolean mysql;

    private Database(Connection c) throws SQLException {
        conn = c;
        mysql = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        // Khác nhau giữa hai CSDL: cột id tự tăng, kiểu bảng; còn lại dùng chung câu lệnh
        String id = mysql ? "id INT AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String tail = mysql ? ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : ")";
        try (Statement st = conn.createStatement()) {
            if (!mysql) st.execute("PRAGMA foreign_keys = ON");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS members (
                        ma_hv      VARCHAR(20) PRIMARY KEY,
                        ho_ten     TEXT NOT NULL,
                        ngay_sinh  TEXT,
                        sdt        TEXT,
                        goi_tap    TEXT,
                        han_goi    TEXT,
                        public_key BLOB NOT NULL,
                        created_at TEXT NOT NULL""" + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS packages (
                        %s,
                        ten      TEXT NOT NULL,
                        so_thang INTEGER NOT NULL,
                        gia      INTEGER NOT NULL""".formatted(id) + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        %s,
                        ma_hv        VARCHAR(20) NOT NULL,
                        loai         TEXT NOT NULL,
                        so_tien      INTEGER NOT NULL,
                        so_du_sau    INTEGER NOT NULL,
                        so_giao_dich INTEGER NOT NULL,
                        noi_dung     TEXT,
                        nonce        BLOB NOT NULL,
                        chu_ky       BLOB NOT NULL,
                        hop_le       INTEGER NOT NULL,
                        thoi_gian    TEXT NOT NULL,
                        FOREIGN KEY (ma_hv) REFERENCES members(ma_hv)""".formatted(id) + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS admins (
                        username   VARCHAR(50) PRIMARY KEY,
                        salt       BLOB NOT NULL,
                        hash       BLOB NOT NULL,
                        iterations INTEGER NOT NULL,
                        created_at TEXT NOT NULL""" + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS lost_cards (
                        %s,
                        ma_hv      VARCHAR(20) NOT NULL,
                        the_so     INTEGER NOT NULL,
                        public_key BLOB NOT NULL,
                        thoi_gian  TEXT NOT NULL,
                        FOREIGN KEY (ma_hv) REFERENCES members(ma_hv)""".formatted(id) + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS checkins (
                        %s,
                        ma_hv     VARCHAR(20) NOT NULL,
                        the_so    INTEGER NOT NULL,
                        thoi_gian TEXT NOT NULL,
                        FOREIGN KEY (ma_hv) REFERENCES members(ma_hv)""".formatted(id) + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS logs (
                        %s,
                        thoi_gian TEXT NOT NULL,
                        admin     TEXT,
                        ma_hv     TEXT,
                        hanh_dong TEXT NOT NULL,
                        chi_tiet  TEXT,
                        canh_bao  INTEGER NOT NULL""".formatted(id) + tail);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS admin_pins (
                        ver        INTEGER PRIMARY KEY,
                        salt       BLOB NOT NULL,
                        hash       BLOB NOT NULL,
                        iterations INTEGER NOT NULL,
                        created_at TEXT NOT NULL""" + tail);
            // Cột thêm sau: CSDL tạo từ bản cũ được bổ sung, dữ liệu giữ nguyên
            addColumnIfMissing("members", "the_so", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("members", "trang_thai", "VARCHAR(30) NOT NULL DEFAULT '" + ACTIVE + "'");
            addColumnIfMissing("transactions", "the_so", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("packages", "dang_ban", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("members", "pin_admin_ver", "INTEGER NOT NULL DEFAULT 1");
            addColumnIfMissing("admins", "rec_salt", "BLOB");
            addColumnIfMissing("admins", "rec_hash", "BLOB");
            addColumnIfMissing("lost_cards", "ly_do", "VARCHAR(30) NOT NULL DEFAULT '" + LOST + "'");
            // Giá theo nghìn đồng, Admin sửa được ở màn Quản lý gói tập
            st.execute("""
                    INSERT %s INTO packages (id, ten, so_thang, gia) VALUES
                        (1, 'Gói 1 tháng', 1, 300), (2, 'Gói 3 tháng', 3, 800),
                        (3, 'Gói 6 tháng', 6, 1500), (4, 'Gói 12 tháng', 12, 2800)""".formatted(mysql ? "IGNORE" : "OR IGNORE"));
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            if (rs.next()) return;
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

    /** Tạo tài khoản Admin; trả về mã khôi phục (chỉ hiện một lần, CSDL chỉ lưu bản băm). */
    public String createAdmin(String username, char[] password) throws SQLException {
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO admins (username, salt, hash, iterations, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setBytes(2, salt);
            ps.setBytes(3, pbkdf2(password, salt, PBKDF2_ITERATIONS));
            ps.setInt(4, PBKDF2_ITERATIONS);
            ps.setString(5, now());
            ps.executeUpdate();
        }
        return newRecoveryCode(username);
    }

    // ---------- Mã khôi phục: dùng khi Admin quên mật khẩu hoặc quên PIN Admin ----------
    // 12 ký tự ngẫu nhiên (bỏ các ký tự dễ nhầm 0/O, 1/I), lưu PBKDF2 có salt như mật khẩu. Dùng xong thì đổi mã mới.

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Sinh mã khôi phục mới cho Admin (mã cũ hết hiệu lực), trả về mã dạng XXXX-XXXX-XXXX. */
    public String newRecoveryCode(String username) throws SQLException {
        SecureRandom r = new SecureRandom();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) code.append('-');
            code.append(CODE_CHARS.charAt(r.nextInt(CODE_CHARS.length())));
        }
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement("UPDATE admins SET rec_salt = ?, rec_hash = ? WHERE username = ?")) {
            ps.setBytes(1, salt);
            ps.setBytes(2, pbkdf2(normalizeCode(code.toString()), salt, PBKDF2_ITERATIONS));
            ps.setString(3, username);
            ps.executeUpdate();
        }
        return code.toString();
    }

    /** Admin đã có mã khôi phục chưa (tài khoản tạo từ bản cũ thì chưa có). */
    public boolean hasRecoveryCode(String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT rec_hash FROM admins WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBytes(1) != null;
            }
        }
    }

    /** Đúng mã khôi phục thì trả true (không phân biệt hoa thường, dấu gạch, dấu cách). */
    public boolean checkRecoveryCode(String username, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT rec_salt, rec_hash FROM admins WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getBytes(2) == null) return false;
                return MessageDigest.isEqual(pbkdf2(normalizeCode(code), rs.getBytes(1), PBKDF2_ITERATIONS), rs.getBytes(2));
            }
        }
    }

    private static char[] normalizeCode(String code) {
        return code.toUpperCase().replaceAll("[^A-Z0-9]", "").toCharArray();
    }

    /**
     * Quên mật khẩu: đúng mã khôi phục thì đặt mật khẩu mới và trả về mã khôi phục mới (mã cũ hết hiệu lực).
     * Sai thì trả null.
     */
    public String resetAdminPassword(String username, String code, char[] newPassword) throws SQLException {
        if (!checkRecoveryCode(username, code)) return null;
        setAdminPassword(username, newPassword);
        return newRecoveryCode(username);
    }

    // ---------- PIN Admin chung của phòng gym ----------
    // Mọi thẻ dùng chung một PIN Admin. CSDL chỉ lưu bản băm PBKDF2, mỗi lần đổi là một phiên bản mới;
    // members.pin_admin_ver cho biết thẻ của hội viên đang dùng phiên bản nào (thẻ cũ được đổi khi ra quầy).

    /** Phiên bản PIN Admin hiện tại, 0 nếu chưa đặt. */
    public int adminPinVersion() throws SQLException {
        return (int) longQuery("SELECT COALESCE(MAX(ver), 0) FROM admin_pins");
    }

    /** Đặt PIN Admin mới (phiên bản kế tiếp), trả về số phiên bản. */
    public int setAdminPin(String pin) throws SQLException {
        int ver = adminPinVersion() + 1;
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO admin_pins VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, ver);
            ps.setBytes(2, salt);
            ps.setBytes(3, pbkdf2(pin.toCharArray(), salt, PBKDF2_ITERATIONS));
            ps.setInt(4, PBKDF2_ITERATIONS);
            ps.setString(5, now());
            ps.executeUpdate();
        }
        return ver;
    }

    /** PIN có đúng PIN Admin của phiên bản ver không. */
    public boolean checkAdminPin(String pin, int ver) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT salt, hash, iterations FROM admin_pins WHERE ver = ?")) {
            ps.setInt(1, ver);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && MessageDigest.isEqual(pbkdf2(pin.toCharArray(), rs.getBytes(1), rs.getInt(3)), rs.getBytes(2));
            }
        }
    }

    /** Phiên bản PIN Admin đang nằm trên thẻ hiện tại của hội viên. */
    public int memberAdminPinVersion(String maHV) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pin_admin_ver FROM members WHERE ma_hv = ?")) {
            ps.setString(1, maHV);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void setMemberAdminPinVersion(String maHV, int ver) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET pin_admin_ver = ? WHERE ma_hv = ?")) {
            ps.setInt(1, ver);
            ps.setString(2, maHV);
            ps.executeUpdate();
        }
    }

    /** Mã hội viên có thẻ hiện tại mang khóa công khai này (dùng khi thẻ bị khóa PIN, chưa đọc được thông tin). */
    public String memberByKey(PublicKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ma_hv FROM members WHERE public_key = ?")) {
            ps.setBytes(1, key.getEncoded());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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
        setAdminPassword(username, newPassword);
        return true;
    }

    private void setAdminPassword(String username, char[] newPassword) throws SQLException {
        byte[] salt = newSalt();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE admins SET salt = ?, hash = ?, iterations = ? WHERE username = ?")) {
            ps.setBytes(1, salt);
            ps.setBytes(2, pbkdf2(newPassword, salt, PBKDF2_ITERATIONS));
            ps.setInt(3, PBKDF2_ITERATIONS);
            ps.setString(4, username);
            ps.executeUpdate();
        }
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
                INSERT INTO members (ma_hv, ho_ten, ngay_sinh, sdt, goi_tap, han_goi, public_key, created_at, pin_admin_ver)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, m.maHV);
            ps.setString(2, m.hoTen);
            ps.setString(3, m.ngaySinh);
            ps.setString(4, m.sdt);
            ps.setString(5, m.goiTap);
            ps.setString(6, m.hanGoi);
            ps.setBytes(7, cardKey.getEncoded());
            ps.setString(8, now());
            ps.setInt(9, adminPinVersion());
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

    public void reportLost(String maHV) throws SQLException {
        disableCard(maHV, LOST);
    }

    /**
     * Vô hiệu thẻ đang dùng: lưu khóa công khai của thẻ vào danh sách thẻ bị vô hiệu, chuyển trạng thái sang
     * reason = LOST (báo mất) hoặc BROKEN (thẻ hỏng, khóa PIN Admin). Sau đó Admin cấp thẻ mới cho hội viên.
     */
    public void disableCard(String maHV, String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO lost_cards (ma_hv, the_so, public_key, thoi_gian, ly_do)
                SELECT ma_hv, the_so, public_key, ?, ? FROM members WHERE ma_hv = ?""")) {
            ps.setString(1, now());
            ps.setString(2, reason);
            ps.setString(3, maHV);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE members SET trang_thai = ? WHERE ma_hv = ?")) {
            ps.setString(1, reason);
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
                UPDATE members SET public_key = ?, the_so = the_so + 1, trang_thai = ?, pin_admin_ver = ?,
                    ho_ten = ?, ngay_sinh = ?, sdt = ?, goi_tap = ?, han_goi = ? WHERE ma_hv = ?""")) {
            ps.setBytes(1, newKey.getEncoded());
            ps.setString(2, ACTIVE);
            ps.setInt(3, adminPinVersion());
            ps.setString(4, m.hoTen);
            ps.setString(5, m.ngaySinh);
            ps.setString(6, m.sdt);
            ps.setString(7, m.goiTap);
            ps.setString(8, m.hanGoi);
            ps.setString(9, m.maHV);
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
        int total = 0, active = 0, expired = 0, lost = 0, broken = 0;
        for (Member m : members("")) {
            total++;
            LocalDate e = expiry(m.info().hanGoi);
            if (e != null && !e.isBefore(today)) active++;
            else if (e != null) expired++;
            if (LOST.equals(m.status())) lost++;
            else if (BROKEN.equals(m.status())) broken++;
        }
        String day = today.format(DATE);                 // dd/MM/yyyy
        String month = "__/" + day.substring(3) + "%";   // __/MM/yyyy%
        return new Stats(total, active, expired, total - active - expired, lost, broken, expiringWithin(7, today).size(),
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
