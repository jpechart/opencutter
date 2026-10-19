package opencutter.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/**
 * Explore Air 2 (HELIUM2) link protocol — framing, checksums, encryption, and key derivation.
 * A direct port of the behaviour recovered from our own machine (docs/PROTOCOL.md).
 *
 * Plaintext frame:  [len][op][payload]                      len = op+payload bytes, NO checksum.
 * Encrypted frame:  [0x80|encLen][AES-256-ECB(inner)]       inner = [len][op][payload] zero-padded to
 *                   the next 16-multiple that leaves a trailing byte; 8-bit checksum of the core in
 *                   the LAST byte.
 * Replies:          [hdr][body]; hdr&0x80 = encrypted; the byte pair 0x5E 0xA2 un-stuffs to 0x24.
 */
public final class Protocol {
    private Protocol() {}

    public static final int NEWAES = 0xC7;
    public static final int RESET_AES = 0xCF;

    /** Machine type id -> 64-byte base buffer from which the per-type KEK is derived. */
    public static final Map<Integer, byte[]> BASE_BUFFERS = Map.of(
        10, hex("204c63cd413760ad33e2142121165655728372dd44c351db15947136336078aa"
              + "21d913388505788b568a3787112c2500011e64e570b231cb56c9868605401618"));

    public static final Map<Integer, String> MACHINE_TYPES = Map.of(10, "Explore Air 2");

    /** KEK[i] = base[63 - 2i]: the odd-indexed bytes of the base buffer, reversed. */
    public static byte[] kekFromBase(byte[] base) {
        if (base.length != 64) throw new IllegalArgumentException("base buffer must be 64 bytes");
        byte[] kek = new byte[32];
        for (int i = 0; i < 32; i++) kek[i] = base[63 - 2 * i];
        return kek;
    }

    public static byte[] kekForType(int machineType) {
        byte[] base = BASE_BUFFERS.get(machineType);
        if (base == null) throw new IllegalArgumentException("no base buffer for machine type " + machineType);
        return kekFromBase(base);
    }

    public static int checksum(byte[] b, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += b[i] & 0xFF;
        return s & 0xFF;
    }

    public static byte[] plainFrame(int op, byte[] payload) {
        byte[] f = new byte[2 + payload.length];
        f[0] = (byte) (1 + payload.length);
        f[1] = (byte) op;
        System.arraycopy(payload, 0, f, 2, payload.length);
        return f;
    }

    public static byte[] encFrame(int op, byte[] payload, byte[] key) {
        byte[] core = plainFrame(op, payload);
        int padded = (core.length / 16 + 1) * 16;
        byte[] inner = new byte[padded];
        System.arraycopy(core, 0, inner, 0, core.length);
        inner[padded - 1] = (byte) checksum(core, core.length);
        byte[] ct = aes(inner, key, Cipher.ENCRYPT_MODE);
        byte[] out = new byte[1 + ct.length];
        out[0] = (byte) (0x80 | (padded & 0x7F));
        System.arraycopy(ct, 0, out, 1, ct.length);
        return out;
    }

    public static byte[] aes(byte[] data, byte[] key, int mode) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES failure", e);
        }
    }

    /** Undo the machine's byte stuffing: 0x5E 0xA2 -> 0x24. */
    public static byte[] unescape(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == 0x5E && i + 1 < in.length && in[i + 1] == (byte) 0xA2) { o.write(0x24); i++; }
            else o.write(in[i]);
        }
        return o.toByteArray();
    }

    /** Body length encoded by a header byte; an encrypted 128-byte body wraps to 0x80. */
    public static int bodyLen(int hdr) {
        return hdr == 0x80 ? 128 : (hdr & 0x7F);
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    public static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static String hex(byte[] b) {
        return hex(b, b.length);
    }

    public static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, b.length); i++) sb.append(String.format("%02x ", b[i] & 0xFF));
        return sb.toString().trim();
    }

    public static byte[] slice(byte[] b, int from, int to) {
        return Arrays.copyOfRange(b, from, Math.min(to, b.length));
    }
}
