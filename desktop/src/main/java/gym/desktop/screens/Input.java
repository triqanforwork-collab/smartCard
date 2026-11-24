package gym.desktop.screens;

import javax.swing.JPasswordField;
import javax.swing.JTextField;

/** Kiểm tra dữ liệu nhập trên các màn hình. Sai thì ném IllegalArgumentException, app.error(e) hiện câu báo lỗi. */
final class Input {
    private Input() {
    }

    /** PIN 4–8 chữ số; again khác null thì phải nhập lại khớp. */
    static String pin(JPasswordField field, JPasswordField again, String what) {
        String p = new String(field.getPassword());
        if (!p.matches("\\d{4,8}")) throw new IllegalArgumentException(what + " phải gồm 4–8 chữ số");
        if (again != null && !p.equals(new String(again.getPassword()))) {
            throw new IllegalArgumentException("Hai ô " + what + " không khớp nhau");
        }
        return p;
    }

    /** Số tiền nghìn đồng: số nguyên dương, tối đa 100 triệu đồng mỗi lần. */
    static long amount(JTextField field) {
        String s = field.getText().trim().replace(".", "").replace(",", "");
        if (!s.matches("\\d{1,6}") || Long.parseLong(s) == 0) {
            throw new IllegalArgumentException("Số tiền phải là số nguyên từ 1 đến 100000 (nghìn đồng)");
        }
        return Long.parseLong(s);
    }
}
