package gym;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.Cipher;

/**
 * Applet the hoi vien phong gym (Java Card 2.2.2).
 *
 * Bang lenh va cach ma hoa: docs/APDU.md. Hang so o day PHAI KHOP voi desktop CardService.java.
 * Viet comment KHONG DAU: JCIDE bien dich bang encoding windows-1252.
 *
 * Ma hoa: luc INIT the sinh khoa chinh K (AES-128). Moi du lieu cua hoi vien ma hoa bang K.
 * K duoc boc (ma hoa) bang khoa sinh tu PIN: KEK = 16 byte dau SHA-256(salt || PIN).
 * Luu 2 ban K da boc: theo PIN nguoi dung va theo PIN Admin.
 * Nhap dung PIN thi mo boc K vao RAM (tu xoa khi rut the).
 */
public class GymApplet extends Applet {

    // ===== Lenh APDU =====
    static final byte CLA             = (byte) 0xB0;
    static final byte INS_GET_STATUS  = (byte) 0x01;
    static final byte INS_INIT        = (byte) 0x10;
    static final byte INS_VERIFY_PIN  = (byte) 0x20;
    static final byte INS_CHANGE_PIN  = (byte) 0x21;
    static final byte INS_UNBLOCK     = (byte) 0x22;
    static final byte INS_RESET_PIN   = (byte) 0x23;
    static final byte INS_CHANGE_ADMIN_PIN = (byte) 0x24;
    static final byte INS_GET_INFO    = (byte) 0x30;
    static final byte INS_UPDATE_INFO = (byte) 0x31;
    static final byte INS_GET_PHOTO   = (byte) 0x32;
    static final byte INS_SET_PHOTO   = (byte) 0x33;
    static final byte INS_GET_BALANCE = (byte) 0x40;
    static final byte INS_TOPUP       = (byte) 0x41;
    static final byte INS_PAY         = (byte) 0x42;
    static final byte INS_GET_PUBKEY  = (byte) 0x50;
    static final byte INS_AUTH        = (byte) 0x51;

    // ===== Ma loi rieng =====
    static final short SW_PIN_BLOCKED = (short) 0x6983;
    static final short SW_TODO        = ISO7816.SW_FUNC_NOT_SUPPORTED; // 6A81 = lenh chua lam
    static final short SW_NOT_ENOUGH_MONEY = (short) 0x6910;

    // ===== Trang thai the (GET_STATUS) =====
    static final byte STATE_BLANK   = (byte) 0;
    static final byte STATE_READY   = (byte) 1;
    static final byte STATE_BLOCKED = (byte) 2;

    // ===== Gioi han =====
    static final byte  PIN_TRY_LIMIT = (byte) 3;
    static final byte  PIN_MIN_LEN   = (byte) 4;
    static final byte  PIN_MAX_LEN   = (byte) 8;
    static final short MAX_INFO      = (short) 200;
    static final short MAX_INFO_ENC  = (short) 208;   // MAX_INFO lam tron len boi so 16
    static final short MAX_PHOTO     = (short) 10240;
    static final short PHOTO_CHUNK   = (short) 240;   // 15 khoi AES

    // ===== Vung nho tam trong RAM (tmp) =====
    static final short T_HASH  = (short) 0;    // 32 byte: ket qua SHA-256
    static final short T_IV    = (short) 32;   // 16 byte: IV
    static final short T_KEY   = (short) 48;   // 16 byte: khoa K dang xu ly
    static final short T_PLAIN = (short) 64;   // 240 byte: ban ro
    static final short T_ENC   = (short) 304;  // 240 byte: ban ma
    static final short TMP_SIZE = (short) 544;

    // ===== Du lieu luu tren the (EEPROM) =====
    private byte state;
    private OwnerPIN userPin, adminPin;
    private byte[] userSalt, adminSalt;       // 16 byte moi cai
    private byte[] userWrappedK, adminWrappedK; // K da boc, 16 byte moi cai
    private byte[] info, infoIv;               // thong tin da ma hoa va IV
    private short infoLen;                     // do dai ban ro
    private byte[] photo, photoIv;              // anh da ma hoa (tung doan 240 byte) va IV goc
    private short photoLen;                    // do dai ban ro
    private byte[] wallet;                     // [so du 4][so giao dich 4][8 byte 0], ma hoa AES bang K
    private KeyPair rsaPair;                   // RSA-1024, sinh luc INIT
    private Signature signer;                  // RSA PKCS#1 kem SHA-1

    // ===== Doi tuong ma hoa, khoa tam (RAM) =====
    private AESKey masterKey; // K sau khi nhap dung PIN, xoa khi rut the
    private AESKey kek;       // khoa sinh tu PIN, chi dung tam trong 1 lenh
    private Cipher aesEcb, aesCbc;
    private MessageDigest sha256;
    private RandomData random;
    private byte[] tmp;

    private GymApplet() {
        userPin = new OwnerPIN(PIN_TRY_LIMIT, PIN_MAX_LEN);
        adminPin = new OwnerPIN(PIN_TRY_LIMIT, PIN_MAX_LEN);
        userSalt = new byte[16];
        adminSalt = new byte[16];
        userWrappedK = new byte[16];
        adminWrappedK = new byte[16];
        info = new byte[MAX_INFO_ENC];
        infoIv = new byte[16];
        photo = new byte[MAX_PHOTO];
        photoIv = new byte[16];
        wallet = new byte[16];
        rsaPair = new KeyPair(KeyPair.ALG_RSA_CRT, KeyBuilder.LENGTH_RSA_1024);
        signer = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);

        masterKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
        kek = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES_TRANSIENT_DESELECT, KeyBuilder.LENGTH_AES_128, false);
        aesEcb = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_ECB_NOPAD, false);
        aesCbc = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
        random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
        tmp = JCSystem.makeTransientByteArray(TMP_SIZE, JCSystem.CLEAR_ON_DESELECT);
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new GymApplet().register(bArray, (short) (bOffset + 1), bArray[bOffset]);
    }

    public void deselect() {
        userPin.reset();
        adminPin.reset();
        masterKey.clearKey();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        byte[] buf = apdu.getBuffer();
        if (buf[ISO7816.OFFSET_CLA] != CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buf[ISO7816.OFFSET_INS]) {
        case INS_GET_STATUS:  getStatus(apdu);  break;
        case INS_INIT:        init(apdu);       break;
        case INS_VERIFY_PIN:  verifyPin(apdu);  break;
        case INS_GET_INFO:    getInfo(apdu);    break;
        case INS_UPDATE_INFO: updateInfo(apdu); break;
        case INS_CHANGE_PIN:  changePin(apdu);  break;
        case INS_UNBLOCK:     unblock(apdu);    break;
        case INS_RESET_PIN:   resetPin(apdu);   break;
        case INS_CHANGE_ADMIN_PIN: changeAdminPin(apdu); break;
        case INS_GET_PHOTO:   getPhoto(apdu);   break;
        case INS_SET_PHOTO:   setPhoto(apdu);   break;
        case INS_GET_BALANCE: getBalance(apdu); break;
        case INS_TOPUP:
        case INS_PAY:         changeBalance(apdu); break;
        case INS_GET_PUBKEY:  getPubKey(apdu);  break;
        case INS_AUTH:        auth(apdu);       break;
        default:
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    // ==================== Trang thai, kiem tra quyen ====================

    /** GET_STATUS: [trang thai][so lan thu PIN con lai]. */
    private void getStatus(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte tries = userPin.getTriesRemaining();
        buf[0] = state == STATE_BLANK ? STATE_BLANK : (tries == 0 ? STATE_BLOCKED : STATE_READY);
        buf[1] = tries;
        apdu.setOutgoingAndSend((short) 0, (short) 2);
    }

    private void requireInitialized() {
        if (state == STATE_BLANK) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
    }

    /** Goi o dau moi lenh can PIN: phai da nhap dung PIN va K da duoc mo trong RAM. */
    private void requirePin() {
        requireInitialized();
        if (!userPin.isValidated() || !masterKey.isInitialized()) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
    }

    /** PIN hop le: dai 4..8, chi gom chu so ASCII. */
    private static void checkPinFormat(byte[] b, short off, short len) {
        if (len < PIN_MIN_LEN || len > PIN_MAX_LEN) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        for (short i = 0; i < len; i++) {
            byte c = b[(short) (off + i)];
            if (c < (byte) '0' || c > (byte) '9') {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
        }
    }

    /** Kiem tra PIN; sai thi bao 63Cx hoac 6983. */
    private static void checkPin(OwnerPIN pin, byte[] b, short off, short len) {
        if (pin.getTriesRemaining() == 0) {
            ISOException.throwIt(SW_PIN_BLOCKED);
        }
        if (!pin.check(b, off, (byte) len)) {
            byte left = pin.getTriesRemaining();
            ISOException.throwIt(left == 0 ? SW_PIN_BLOCKED : (short) (0x63C0 | left));
        }
    }

    // ==================== Khoa sinh tu PIN, boc / mo boc K ====================

    /** kek = 16 byte dau cua SHA-256(salt || PIN). */
    private void deriveKek(byte[] salt, short saltOff, byte[] pin, short off, short len) {
        sha256.reset();
        sha256.update(salt, saltOff, (short) 16);
        sha256.doFinal(pin, off, len, tmp, T_HASH);
        kek.setKey(tmp, T_HASH);
        Util.arrayFillNonAtomic(tmp, T_HASH, (short) 32, (byte) 0);
    }

    /** Boc K (dang o tmp[T_KEY]) bang kek, ghi vao dest[destOff]. */
    private void wrapKey(byte[] dest, short destOff) {
        aesEcb.init(kek, Cipher.MODE_ENCRYPT);
        aesEcb.doFinal(tmp, T_KEY, (short) 16, dest, destOff);
    }

    /**
     * Dat PIN nguoi dung moi va boc lai K (dang nam trong masterKey) theo PIN moi voi salt moi.
     * Du lieu da ma hoa bang K giu nguyen, khong phai ma hoa lai.
     */
    private void setUserPin(byte[] pin, short off, short len) {
        masterKey.getKey(tmp, T_KEY);
        random.generateData(tmp, T_IV, (short) 16);       // salt moi
        deriveKek(tmp, T_IV, pin, off, len);
        wrapKey(tmp, T_ENC);                               // K boc theo PIN moi
        Util.arrayFillNonAtomic(tmp, T_KEY, (short) 16, (byte) 0);
        kek.clearKey();

        JCSystem.beginTransaction();
        userPin.update(pin, off, (byte) len);
        Util.arrayCopy(tmp, T_IV, userSalt, (short) 0, (short) 16);
        Util.arrayCopy(tmp, T_ENC, userWrappedK, (short) 0, (short) 16);
        JCSystem.commitTransaction();
    }

    /** Mo boc K tu src bang kek, nap vao masterKey. */
    private void unwrapKey(byte[] src) {
        aesEcb.init(kek, Cipher.MODE_DECRYPT);
        aesEcb.doFinal(src, (short) 0, (short) 16, tmp, T_KEY);
        masterKey.setKey(tmp, T_KEY);
        Util.arrayFillNonAtomic(tmp, T_KEY, (short) 16, (byte) 0);
    }

    // ==================== PIN ====================

    /** VERIFY_PIN. Data: [PIN]. Dung -> 9000 va mo K; sai -> 63Cx; bi khoa -> 6983. */
    private void verifyPin(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        checkPin(userPin, buf, ISO7816.OFFSET_CDATA, len);
        deriveKek(userSalt, (short) 0, buf, ISO7816.OFFSET_CDATA, len);
        unwrapKey(userWrappedK);
    }

    /** CHANGE_PIN. Data: [do dai PIN cu][PIN cu][PIN moi]. Xong van dang dang nhap bang PIN moi. */
    private void changePin(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        short oldOff = (short) (ISO7816.OFFSET_CDATA + 1);
        short oldLen = buf[ISO7816.OFFSET_CDATA];
        short newOff = (short) (oldOff + oldLen);
        short newLen = (short) (ISO7816.OFFSET_CDATA + len - newOff);
        if (len < 1 || oldLen < 0 || newLen < 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        checkPinFormat(buf, newOff, newLen);
        checkPin(userPin, buf, oldOff, oldLen);
        deriveKek(userSalt, (short) 0, buf, oldOff, oldLen);
        unwrapKey(userWrappedK);
        setUserPin(buf, newOff, newLen);
        userPin.check(buf, newOff, (byte) newLen); // giu trang thai da nhap PIN
    }

    /** UNBLOCK. Data: [PIN Admin]. Mo khoa PIN nguoi dung, PIN giu nguyen, so lan thu ve 3. */
    private void unblock(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        checkPin(adminPin, apdu.getBuffer(), ISO7816.OFFSET_CDATA, len);
        userPin.resetAndUnblock();
        adminPin.reset();
    }

    /** RESET_PIN. Data: [do dai PIN Admin][PIN Admin][PIN moi]. Mo K bang PIN Admin roi boc lai theo PIN moi. */
    private void resetPin(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        short admOff = (short) (ISO7816.OFFSET_CDATA + 1);
        short admLen = buf[ISO7816.OFFSET_CDATA];
        short newOff = (short) (admOff + admLen);
        short newLen = (short) (ISO7816.OFFSET_CDATA + len - newOff);
        if (len < 1 || admLen < 0 || newLen < 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        checkPinFormat(buf, newOff, newLen);
        checkPin(adminPin, buf, admOff, admLen);
        deriveKek(adminSalt, (short) 0, buf, admOff, admLen);
        unwrapKey(adminWrappedK);
        setUserPin(buf, newOff, newLen);
        masterKey.clearKey(); // hoi vien phai nhap PIN moi moi xem duoc du lieu
        adminPin.reset();
    }

    /**
     * CHANGE_ADMIN_PIN. Data: [do dai PIN Admin cu][PIN Admin cu][PIN Admin moi].
     * Mo K bang PIN Admin cu roi boc lai K theo PIN Admin moi voi salt moi. Du lieu va PIN hoi vien giu nguyen.
     */
    private void changeAdminPin(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        short oldOff = (short) (ISO7816.OFFSET_CDATA + 1);
        short oldLen = buf[ISO7816.OFFSET_CDATA];
        short newOff = (short) (oldOff + oldLen);
        short newLen = (short) (ISO7816.OFFSET_CDATA + len - newOff);
        if (len < 1 || oldLen < 0 || newLen < 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        checkPinFormat(buf, newOff, newLen);
        checkPin(adminPin, buf, oldOff, oldLen);
        boolean loggedIn = userPin.isValidated() && masterKey.isInitialized();
        deriveKek(adminSalt, (short) 0, buf, oldOff, oldLen);
        unwrapKey(adminWrappedK);

        masterKey.getKey(tmp, T_KEY);
        random.generateData(tmp, T_IV, (short) 16);       // salt moi
        deriveKek(tmp, T_IV, buf, newOff, newLen);
        wrapKey(tmp, T_ENC);
        Util.arrayFillNonAtomic(tmp, T_KEY, (short) 16, (byte) 0);
        kek.clearKey();
        if (!loggedIn) {
            masterKey.clearKey(); // chi PIN hoi vien moi mo duoc du lieu
        }

        JCSystem.beginTransaction();
        adminPin.update(buf, newOff, (byte) newLen);
        Util.arrayCopy(tmp, T_IV, adminSalt, (short) 0, (short) 16);
        Util.arrayCopy(tmp, T_ENC, adminWrappedK, (short) 0, (short) 16);
        JCSystem.commitTransaction();
        adminPin.reset();
    }

    // ==================== Khoi tao ====================

    /**
     * INIT (chi chay duoc 1 lan).
     * Data: [do dai PIN][PIN][do dai PIN Admin][PIN Admin][thong tin].
     */
    private void init(APDU apdu) {
        if (state != STATE_BLANK) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        short end = (short) (ISO7816.OFFSET_CDATA + len);

        short pinOff = (short) (ISO7816.OFFSET_CDATA + 1);
        short pinLen = len < 1 ? (short) 0 : buf[ISO7816.OFFSET_CDATA];
        short admLenOff = (short) (pinOff + pinLen);
        if (pinLen < 0 || admLenOff >= end) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short admOff = (short) (admLenOff + 1);
        short admLen = buf[admLenOff];
        short infoOff = (short) (admOff + admLen);
        if (admLen < 0 || infoOff > end) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short infoLength = (short) (end - infoOff);
        checkPinFormat(buf, pinOff, pinLen);
        checkPinFormat(buf, admOff, admLen);
        if (infoLength > MAX_INFO) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        userPin.update(buf, pinOff, (byte) pinLen);
        adminPin.update(buf, admOff, (byte) admLen);
        random.generateData(userSalt, (short) 0, (short) 16);
        random.generateData(adminSalt, (short) 0, (short) 16);

        // Sinh K, boc theo PIN nguoi dung va theo PIN Admin
        random.generateData(tmp, T_KEY, (short) 16);
        deriveKek(userSalt, (short) 0, buf, pinOff, pinLen);
        wrapKey(userWrappedK, (short) 0);
        deriveKek(adminSalt, (short) 0, buf, admOff, admLen);
        wrapKey(adminWrappedK, (short) 0);

        // Ma hoa thong tin bang K roi xoa K khoi RAM: muon doc phai VERIFY_PIN
        masterKey.setKey(tmp, T_KEY);
        Util.arrayFillNonAtomic(tmp, T_KEY, (short) 16, (byte) 0);
        storeInfo(buf, infoOff, infoLength);
        Util.arrayFillNonAtomic(tmp, T_PLAIN, (short) 16, (byte) 0); // vi: so du 0, chua co giao dich
        storeWallet();
        masterKey.clearKey();
        kek.clearKey();

        rsaPair.genKeyPair();
        state = STATE_READY;
    }

    // ==================== Thong tin hoi vien ====================

    /** Ma hoa len khoi thong tin (AES-CBC, IV moi) va ghi vao EEPROM trong 1 giao dich. */
    private void storeInfo(byte[] src, short off, short len) {
        short padded = (short) ((short) (len + 15) & (short) 0xFFF0);
        Util.arrayCopyNonAtomic(src, off, tmp, T_PLAIN, len);
        Util.arrayFillNonAtomic(tmp, (short) (T_PLAIN + len), (short) (padded - len), (byte) 0);
        random.generateData(tmp, T_IV, (short) 16);
        if (padded > 0) {
            aesCbc.init(masterKey, Cipher.MODE_ENCRYPT, tmp, T_IV, (short) 16);
            aesCbc.doFinal(tmp, T_PLAIN, padded, tmp, T_ENC);
        }
        Util.arrayFillNonAtomic(tmp, T_PLAIN, padded, (byte) 0);

        JCSystem.beginTransaction();
        Util.arrayCopy(tmp, T_ENC, info, (short) 0, padded);
        Util.arrayCopy(tmp, T_IV, infoIv, (short) 0, (short) 16);
        infoLen = len;
        JCSystem.commitTransaction();
    }

    /** GET_INFO (can PIN). Tra ve khoi thong tin da giai ma. */
    private void getInfo(APDU apdu) {
        requirePin();
        byte[] buf = apdu.getBuffer();
        short padded = (short) ((short) (infoLen + 15) & (short) 0xFFF0);
        if (padded > 0) {
            aesCbc.init(masterKey, Cipher.MODE_DECRYPT, infoIv, (short) 0, (short) 16);
            aesCbc.doFinal(info, (short) 0, padded, tmp, T_PLAIN);
        }
        Util.arrayCopyNonAtomic(tmp, T_PLAIN, buf, (short) 0, infoLen);
        Util.arrayFillNonAtomic(tmp, T_PLAIN, padded, (byte) 0);
        apdu.setOutgoingAndSend((short) 0, infoLen);
    }

    /** UPDATE_INFO (can PIN). Data: khoi thong tin moi (thay toan bo khoi cu). */
    private void updateInfo(APDU apdu) {
        requirePin();
        short len = apdu.setIncomingAndReceive();
        if (len > MAX_INFO) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        storeInfo(apdu.getBuffer(), ISO7816.OFFSET_CDATA, len);
    }

    // ==================== Anh ====================

    /** IV cua doan anh = baseIv voi 2 byte cuoi XOR so thu tu doan. Ghi vao tmp[T_IV]. */
    private void chunkIv(byte[] baseIv, short baseOff, short offset) {
        Util.arrayCopyNonAtomic(baseIv, baseOff, tmp, T_IV, (short) 16);
        short idx = (short) (offset / PHOTO_CHUNK);
        Util.setShort(tmp, (short) (T_IV + 14), (short) (Util.getShort(tmp, (short) (T_IV + 14)) ^ idx));
    }

    /**
     * SET_PHOTO (can PIN). P1P2 = offset (boi so 240), Data = 1 doan toi da 240 byte.
     * Offset 0 bat dau anh moi; cac doan sau phai gui noi tiep nhau.
     */
    private void setPhoto(APDU apdu) {
        requirePin();
        byte[] buf = apdu.getBuffer();
        short offset = Util.getShort(buf, ISO7816.OFFSET_P1);
        short len = apdu.setIncomingAndReceive();
        if (len > PHOTO_CHUNK) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        boolean newPhoto = offset == 0;
        if (offset < 0 || (short) (offset % PHOTO_CHUNK) != 0 || (short) (offset + len) > MAX_PHOTO
                || (!newPhoto && (offset != photoLen || (short) (photoLen % PHOTO_CHUNK) != 0))) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        // Anh moi: IV goc moi (tam de o tmp[T_HASH], ghi vao EEPROM cung luc voi doan dau)
        if (newPhoto) {
            random.generateData(tmp, T_HASH, (short) 16);
            chunkIv(tmp, T_HASH, offset);
        } else {
            chunkIv(photoIv, (short) 0, offset);
        }
        short padded = (short) ((short) (len + 15) & (short) 0xFFF0);
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, tmp, T_PLAIN, len);
        Util.arrayFillNonAtomic(tmp, (short) (T_PLAIN + len), (short) (padded - len), (byte) 0);
        if (padded > 0) {
            aesCbc.init(masterKey, Cipher.MODE_ENCRYPT, tmp, T_IV, (short) 16);
            aesCbc.doFinal(tmp, T_PLAIN, padded, tmp, T_ENC);
        }
        Util.arrayFillNonAtomic(tmp, T_PLAIN, padded, (byte) 0);

        JCSystem.beginTransaction();
        if (newPhoto) {
            Util.arrayCopy(tmp, T_HASH, photoIv, (short) 0, (short) 16);
        }
        Util.arrayCopy(tmp, T_ENC, photo, offset, padded);
        photoLen = (short) (offset + len);
        JCSystem.commitTransaction();
    }

    /** GET_PHOTO (can PIN). P1P2 = offset (boi so 240). Tra toi da 240 byte; it hon 240 = het anh. */
    private void getPhoto(APDU apdu) {
        requirePin();
        byte[] buf = apdu.getBuffer();
        short offset = Util.getShort(buf, ISO7816.OFFSET_P1);
        if (offset < 0 || (short) (offset % PHOTO_CHUNK) != 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }
        short n = offset >= photoLen ? (short) 0 : (short) (photoLen - offset);
        if (n > PHOTO_CHUNK) {
            n = PHOTO_CHUNK;
        }
        short padded = (short) ((short) (n + 15) & (short) 0xFFF0);
        if (padded > 0) {
            chunkIv(photoIv, (short) 0, offset);
            aesCbc.init(masterKey, Cipher.MODE_DECRYPT, tmp, T_IV, (short) 16);
            aesCbc.doFinal(photo, offset, padded, tmp, T_PLAIN);
        }
        Util.arrayCopyNonAtomic(tmp, T_PLAIN, buf, (short) 0, n);
        Util.arrayFillNonAtomic(tmp, T_PLAIN, padded, (byte) 0);
        apdu.setOutgoingAndSend((short) 0, n);
    }

    // ==================== RSA ====================

    /** GET_PUBKEY. P1 = 0: modulus (128 byte), P1 = 1: exponent. */
    private void getPubKey(APDU apdu) {
        requireInitialized();
        byte[] buf = apdu.getBuffer();
        RSAPublicKey pub = (RSAPublicKey) rsaPair.getPublic();
        short n = buf[ISO7816.OFFSET_P1] == 0 ? pub.getModulus(buf, (short) 0) : pub.getExponent(buf, (short) 0);
        apdu.setOutgoingAndSend((short) 0, n);
    }

    /** Ky msgLen byte tai tmp[msgOff], chu ky (128 byte) ghi vao tmp[T_ENC]. */
    private short sign(short msgOff, short msgLen) {
        signer.init(rsaPair.getPrivate(), Signature.MODE_SIGN);
        return signer.sign(tmp, msgOff, msgLen, tmp, T_ENC);
    }

    /** AUTH. Data: [nonce 16]. Tra ve chu ky cua [51][nonce] de may tinh kiem tra the that. */
    private void auth(APDU apdu) {
        requireInitialized();
        short len = apdu.setIncomingAndReceive();
        if (len != 16) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        byte[] buf = apdu.getBuffer();
        tmp[T_PLAIN] = INS_AUTH;
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, tmp, (short) (T_PLAIN + 1), (short) 16);
        short sigLen = sign(T_PLAIN, (short) 17);
        Util.arrayCopyNonAtomic(tmp, T_ENC, buf, (short) 0, sigLen);
        apdu.setOutgoingAndSend((short) 0, sigLen);
    }

    // ==================== Vi ====================

    /** Giai ma vi vao tmp[T_PLAIN] (16 byte). */
    private void loadWallet() {
        aesEcb.init(masterKey, Cipher.MODE_DECRYPT);
        aesEcb.doFinal(wallet, (short) 0, (short) 16, tmp, T_PLAIN);
    }

    /** Ma hoa tmp[T_PLAIN] (16 byte) roi ghi vao vi trong 1 giao dich. */
    private void storeWallet() {
        aesEcb.init(masterKey, Cipher.MODE_ENCRYPT);
        aesEcb.doFinal(tmp, T_PLAIN, (short) 16, tmp, T_ENC);
        JCSystem.beginTransaction();
        Util.arrayCopy(tmp, T_ENC, wallet, (short) 0, (short) 16);
        JCSystem.commitTransaction();
    }

    /** a = a + b (so 4 byte khong dau). Tra true neu tran. */
    private static boolean add32(byte[] a, short aOff, byte[] b, short bOff) {
        short carry = 0;
        for (short i = 3; i >= 0; i--) {
            short s = (short) ((short) (a[(short) (aOff + i)] & 0xFF) + (short) (b[(short) (bOff + i)] & 0xFF) + carry);
            a[(short) (aOff + i)] = (byte) s;
            carry = (short) (s >> 8);
        }
        return carry != 0;
    }

    /** a = a - b (so 4 byte khong dau). Tra true neu am (khong du). */
    private static boolean sub32(byte[] a, short aOff, byte[] b, short bOff) {
        short borrow = 0;
        for (short i = 3; i >= 0; i--) {
            short s = (short) ((short) (a[(short) (aOff + i)] & 0xFF) - (short) (b[(short) (bOff + i)] & 0xFF) - borrow);
            borrow = 0;
            if (s < 0) {
                s += 256;
                borrow = 1;
            }
            a[(short) (aOff + i)] = (byte) s;
        }
        return borrow != 0;
    }

    /** GET_BALANCE (can PIN). Tra ve [so du 4][so giao dich 4]. */
    private void getBalance(APDU apdu) {
        requirePin();
        loadWallet();
        byte[] buf = apdu.getBuffer();
        Util.arrayCopyNonAtomic(tmp, T_PLAIN, buf, (short) 0, (short) 8);
        Util.arrayFillNonAtomic(tmp, T_PLAIN, (short) 16, (byte) 0);
        apdu.setOutgoingAndSend((short) 0, (short) 8);
    }

    /**
     * TOPUP / PAY (can PIN hoi vien).
     * PAY:   Data = [so tien 4][nonce 16].
     * TOPUP: Data = [do dai PIN Admin][PIN Admin][so tien 4][nonce 16]
     *        (nap tien la viec cua nhan vien thu tien, nen the doi them PIN Admin;
     *         chuong trinh tu gui lenh nap tien ma khong co PIN Admin se bi tu choi).
     * Tra ve [so du moi 4][so giao dich 4][chu ky 128],
     * chu ky cua [INS][so tien 4][nonce 16][so du moi 4][so giao dich 4] (khong chua PIN Admin).
     */
    private void changeBalance(APDU apdu) {
        requirePin();
        short len = apdu.setIncomingAndReceive();
        byte[] buf = apdu.getBuffer();
        byte ins = buf[ISO7816.OFFSET_INS];
        short amt = ISO7816.OFFSET_CDATA;
        if (ins == INS_TOPUP) {
            short admLen = len < 1 ? (short) 0 : buf[ISO7816.OFFSET_CDATA];
            if (len < 1 || admLen < 0 || len != (short) (1 + admLen + 20)) {
                ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            }
            checkPin(adminPin, buf, (short) (ISO7816.OFFSET_CDATA + 1), admLen);
            adminPin.reset();
            amt = (short) (ISO7816.OFFSET_CDATA + 1 + admLen);
        } else if (len != 20) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if (Util.getShort(buf, amt) == 0 && Util.getShort(buf, (short) (amt + 2)) == 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        loadWallet();
        if (ins == INS_TOPUP) {
            if (add32(tmp, T_PLAIN, buf, amt)) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA); // vuot gioi han 4 byte
            }
        } else if (sub32(tmp, T_PLAIN, buf, amt)) {
            ISOException.throwIt(SW_NOT_ENOUGH_MONEY);
        }
        // so giao dich + 1
        for (short i = (short) (T_PLAIN + 7); i >= (short) (T_PLAIN + 4); i--) {
            if (++tmp[i] != 0) {
                break;
            }
        }
        storeWallet();

        // Noi dung ky dat o tmp[T_PLAIN + 16]: [INS][so tien][nonce][so du moi][so giao dich]
        short m = (short) (T_PLAIN + 16);
        tmp[m] = ins;
        Util.arrayCopyNonAtomic(buf, amt, tmp, (short) (m + 1), (short) 20);
        Util.arrayCopyNonAtomic(tmp, T_PLAIN, tmp, (short) (m + 21), (short) 8);
        short sigLen = sign(m, (short) 29);

        Util.arrayCopyNonAtomic(tmp, T_PLAIN, buf, (short) 0, (short) 8);
        Util.arrayCopyNonAtomic(tmp, T_ENC, buf, (short) 8, sigLen);
        Util.arrayFillNonAtomic(tmp, T_PLAIN, (short) 48, (byte) 0);
        apdu.setOutgoingAndSend((short) 0, (short) (8 + sigLen));
    }
}
