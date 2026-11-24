package gym.desktop;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Thông tin hội viên lưu trên thẻ. Thẻ chỉ lưu 1 khối byte, việc tách trường do máy tính làm.
 * Định dạng: các trường nối bằng '\n', mã UTF-8, tối đa MAX_BYTES (xem docs/APDU.md).
 */
public class MemberInfo {
    public static final int MAX_BYTES = 200; // khớp MAX_INFO trong GymApplet

    public String maHV = "", hoTen = "", ngaySinh = "", sdt = "", goiTap = "", hanGoi = "";

    public byte[] toBytes() {
        byte[] b = String.join("\n", maHV, hoTen, ngaySinh, sdt, goiTap, hanGoi).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_BYTES)
            throw new IllegalArgumentException("Thông tin quá dài: " + b.length + "/" + MAX_BYTES + " byte");
        return b;
    }

    /** Kiểm tra dữ liệu trước khi ghi lên thẻ; sai thì ném IllegalArgumentException với câu báo lỗi. */
    public void validate() {
        if (!maHV.matches("[A-Za-z0-9]{1,10}")) throw new IllegalArgumentException("Mã hội viên gồm 1–10 chữ cái hoặc số, không dấu");
        if (hoTen.isBlank()) throw new IllegalArgumentException("Chưa nhập họ tên");
        LocalDate birth = date(ngaySinh, "Ngày sinh");
        if (!birth.isBefore(LocalDate.now())) throw new IllegalArgumentException("Ngày sinh phải trước hôm nay");
        if (!sdt.matches("0\\d{9,10}")) throw new IllegalArgumentException("Số điện thoại phải bắt đầu bằng 0, gồm 10–11 chữ số");
        if (!hanGoi.isBlank()) date(hanGoi, "Hạn gói");
        toBytes(); // báo lỗi nếu tổng cộng quá 200 byte
    }

    private static LocalDate date(String s, String what) {
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " phải có dạng dd/mm/yyyy và là ngày có thật");
        }
    }

    public static MemberInfo fromBytes(byte[] b) {
        String[] f = new String(b, StandardCharsets.UTF_8).split("\n", -1);
        MemberInfo m = new MemberInfo();
        m.maHV = field(f, 0);
        m.hoTen = field(f, 1);
        m.ngaySinh = field(f, 2);
        m.sdt = field(f, 3);
        m.goiTap = field(f, 4);
        m.hanGoi = field(f, 5);
        return m;
    }

    private static String field(String[] f, int i) {
        return i < f.length ? f[i] : "";
    }
}
