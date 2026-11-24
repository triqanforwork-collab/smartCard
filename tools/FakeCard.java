import javax.smartcardio.*;
import java.nio.charset.StandardCharsets;

/**
 * Tạo "thẻ giả" để demo: khởi tạo thẻ trắng (vừa bấm Run trong JCIDE) với thông tin của một hội viên
 * đã có trong CSDL, nhưng không qua phần mềm phòng gym. Thẻ này có cặp khóa RSA khác với khóa đã đăng ký,
 * nên khi đăng nhập, phần mềm kiểm tra chữ ký AUTH sẽ phát hiện và từ chối.
 *
 * Cách chạy:  java tools/FakeCard.java HV100 "Lê Văn An" 1234
 */
public class FakeCard {
    public static void main(String[] a) throws Exception {
        if (a.length < 3) {
            System.out.println("Cách dùng: java tools/FakeCard.java <mã HV> <họ tên> <PIN>");
            return;
        }
        String info = String.join("\n", a[0], a[1], "01/01/2000", "0900000000", "Gói 12 tháng", "31/12/2030");
        byte[] pin = a[2].getBytes(StandardCharsets.US_ASCII), admin = "11111111".getBytes(StandardCharsets.US_ASCII);
        byte[] i = info.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[2 + pin.length + admin.length + i.length];
        d[0] = (byte) pin.length;
        System.arraycopy(pin, 0, d, 1, pin.length);
        d[1 + pin.length] = (byte) admin.length;
        System.arraycopy(admin, 0, d, 2 + pin.length, admin.length);
        System.arraycopy(i, 0, d, 2 + pin.length + admin.length, i.length);

        CardTerminal t = TerminalFactory.getDefault().terminals().list().stream()
                .filter(x -> x.getName().contains("Contact Reader")).findFirst().orElseThrow();
        CardChannel ch = t.connect("*").getBasicChannel();
        ch.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{0x47, 0x59, 0x4D, 0x43, 0x41, 0x52, 0x44, 0x01}));
        int sw = ch.transmit(new CommandAPDU(0xB0, 0x10, 0, 0, d)).getSW();
        System.out.println(sw == 0x9000
                ? "Đã tạo thẻ giả mang mã " + a[0] + ", PIN " + a[2] + ". Mở phần mềm, kết nối và đăng nhập để thấy thẻ bị từ chối."
                : String.format("Không tạo được (SW %04X). Thẻ phải là thẻ trắng: bấm Run lại trong JCIDE.", sw));
    }
}
