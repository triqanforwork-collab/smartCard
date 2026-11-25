package gym.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import gym.desktop.screens.*;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/** Cửa sổ chính: chứa các màn hình, chuyển màn hình bằng go(tên). */
public class MainFrame extends JFrame {
    public static final String CONNECT = "Kết nối thẻ", PIN = "Nhập mã PIN", INIT = "Khởi tạo thẻ",
            INFO = "Thông tin hội viên", CHANGE_PIN = "Đổi mã PIN", UNBLOCK = "Mở khóa thẻ / Đặt lại PIN",
            TOPUP = "Nạp tiền", PAY = "Gia hạn gói tập", HISTORY = "Lịch sử giao dịch", MEMBERS = "Danh sách hội viên",
            ADMIN_LOGIN = "Đăng nhập Admin", ADMIN_HOME = "Quản trị", CHECKIN = "Check-in",
            LOGS = "Nhật ký hoạt động", PACKAGES = "Quản lý gói tập", ADMIN_PASSWORD = "Đổi mật khẩu Admin";

    /** Các màn chỉ dành cho Admin: tự đăng xuất Admin thì rời khỏi các màn này. */
    static final java.util.Set<String> ADMIN_SCREENS = java.util.Set.of(INIT, UNBLOCK, TOPUP, MEMBERS, ADMIN_HOME,
            LOGS, PACKAGES, ADMIN_PASSWORD, HISTORY);
    /** Không thao tác chuột / bàn phím quá số phút này thì tự đăng xuất Admin. */
    public static final int ADMIN_IDLE_MINUTES = 5;
    /** Thời gian chờ tính bằng ms; chạy với -Dgym.adminIdleMs=20000 để thử nhanh. */
    static final long ADMIN_IDLE_MS = Long.getLong("gym.adminIdleMs", ADMIN_IDLE_MINUTES * 60_000L);

    public final CardService card = new CardService();
    public final Database db;
    public final MemberService members;
    public final WalletService wallet;
    /** Hội viên của thẻ đang đăng nhập (null khi chưa đăng nhập). */
    public MemberInfo member;
    /** Tên Admin đang đăng nhập phần mềm (null khi chưa đăng nhập). Đổi bằng setAdmin(). */
    public String admin;
    /** Hội viên đang được cấp lại thẻ (chọn ở Danh sách hội viên); null khi phát hành thẻ mới bình thường. */
    public MemberInfo reissue;

    private String screen = CONNECT;
    private long lastActivity = System.currentTimeMillis();

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    public MainFrame(Database db) {
        super("Thẻ hội viên phòng gym");
        this.db = db;
        members = new MemberService(card, db);
        wallet = new WalletService(card, db);

        // Mỗi màn là một JPanel Form (kéo thả), tự gọi setName(tên màn) trong constructor (xem PinForm).
        JPanel[] screens = {new ConnectForm(this), new PinForm(this), new InitCardForm(this),
                new InfoForm(this), new ChangePinForm(this), new UnblockForm(this), new TopUpForm(this),
                new PayForm(this), new HistoryForm(this), new MembersForm(this), new AdminLoginForm(this),
                new AdminHomeForm(this), new CheckInForm(this), new LogsForm(this), new PackagesForm(this),
                new AdminPasswordForm(this)};
        for (JPanel s : screens) root.add(s, s.getName());

        // Tự đăng xuất Admin khi không thao tác: ghi lại lần cuối dùng chuột / bàn phím, 15 giây kiểm tra một lần
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> lastActivity = System.currentTimeMillis(),
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        new Timer((int) Math.min(15_000, ADMIN_IDLE_MS / 2), e -> {
            if (admin != null && System.currentTimeMillis() - lastActivity > ADMIN_IDLE_MS) {
                long sec = ADMIN_IDLE_MS / 1000;
                String time = sec % 60 == 0 ? sec / 60 + " phút" : sec + " giây";
                db.log(null, "Tự đăng xuất Admin", "Không thao tác " + time, false);
                setAdmin(null);
                if (ADMIN_SCREENS.contains(screen)) go(CONNECT);
                JOptionPane.showMessageDialog(this, "Đã tự đăng xuất Admin do không thao tác " + time + ".");
            }
        }).start();

        setContentPane(root);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                card.disconnect();
                try {
                    db.close();
                } catch (SQLException ignored) {
                }
            }
        });
        setSize(900, 600);
        setLocationRelativeTo(null);
    }

    public void go(String screen) {
        this.screen = screen;
        cards.show(root, screen);
    }

    /** Đăng nhập (tên Admin) hoặc đăng xuất Admin (null). Nhật ký ghi kèm tên Admin đang đăng nhập. */
    public void setAdmin(String name) {
        admin = name;
        db.setCurrentAdmin(name);
        reissue = null;
    }

    /** Đăng xuất: quên hội viên đang đăng nhập, ngắt thẻ, về màn Kết nối. */
    public void logout() {
        member = null;
        card.disconnect();
        go(CONNECT);
    }

    /** Gọi ở đầu mỗi chức năng quản trị: phát hành thẻ, nạp tiền, danh sách hội viên, mở khóa / đặt lại PIN. */
    public void requireAdmin() {
        if (admin == null) {
            throw new IllegalStateException("Chức năng này cần Admin đăng nhập (màn Kết nối thẻ → Quản trị)");
        }
    }

    public void error(Exception e) {
        e.printStackTrace();
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        JOptionPane.showMessageDialog(this, msg, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> {
            try {
                new MainFrame(Database.open()).setVisible(true);
            } catch (SQLException | java.io.IOException e) {
                JOptionPane.showMessageDialog(null, "Không mở được CSDL (gym.db, hoặc MySQL theo db.properties): " + e.getMessage(),
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ---------- Ảnh (dùng chung cho màn Khởi tạo và màn Thông tin) ----------

    public static void showPhoto(JLabel photo, byte[] img) {
        boolean has = img != null && img.length > 0;
        photo.setText(has ? "" : "Chưa có ảnh");
        photo.setIcon(has ? new ImageIcon(new ImageIcon(img).getImage()
                .getScaledInstance(100, 120, Image.SCALE_SMOOTH)) : null);
    }

    /** Mở hộp chọn ảnh, trả về ảnh đã thu nhỏ và nén vừa thẻ (null nếu bấm Hủy). */
    public static byte[] chooseImage(Component parent) throws IOException {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Ảnh (jpg, png)", "jpg", "jpeg", "png"));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        BufferedImage img = ImageIO.read(fc.getSelectedFile());
        if (img == null) throw new IOException("Không đọc được file ảnh này");
        return compressPhoto(img);
    }

    /**
     * Thu nhỏ ảnh vào khung 120x150 (giữ tỉ lệ) rồi nén JPEG, giảm dần chất lượng
     * cho đến khi vừa CardService.MAX_PHOTO (10240 byte, dung lượng ảnh trên thẻ).
     */
    public static byte[] compressPhoto(BufferedImage src) throws IOException {
        double scale = Math.min(1.0, Math.min(120.0 / src.getWidth(), 150.0 / src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); // nền trắng cho ảnh PNG trong suốt
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try {
            for (float q = 0.9f; q > 0.05f; q -= 0.1f) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageWriteParam p = writer.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(img, null, null), p);
                }
                if (out.size() <= CardService.MAX_PHOTO) return out.toByteArray();
            }
        } finally {
            writer.dispose();
        }
        throw new IOException("Không nén được ảnh xuống dưới 10 KB");
    }
}
