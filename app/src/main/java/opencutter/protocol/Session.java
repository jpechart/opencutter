package opencutter.protocol;

import static opencutter.protocol.Protocol.*;

/**
 * One open link to a machine (USB serial or Bluetooth RFCOMM — see {@link Link}).
 * Plaintext queries work with no session; motion requires the NewAES handshake first.
 * All link access is serialized on this object, so the job thread and the UI thread may share it.
 */
public final class Session implements AutoCloseable {
    private final Link link;
    private final byte[] kek;
    private byte[] key;   // 32-byte AES session key, null until handshake()

    public Session(Link link, byte[] kek) {
        this.link = link;
        this.kek = kek;
    }

    /** USB (or any live COM port). */
    public static Session usb(String portName, byte[] kek) {
        return new Session(new SerialLink(portName), kek);
    }

    /** Bluetooth: connect to the machine's MAC on RFCOMM channel 6 (brings the link up). */
    public static Session bluetooth(String mac, byte[] kek) {
        return new Session(new BtLink(mac, 6), kek);
    }

    public byte[] sessionKey() { return key; }

    // --- plaintext commands (no session required) ---

    public synchronized byte[] query(int op, byte[] payload, long waitMs) {
        link.write(plainFrame(op, payload));
        return parseBody(link.readReply(waitMs));
    }

    public byte[] query(int op) { return query(op, new byte[0], 1000); }

    public byte[] machineStatus() { return query(0x60, hex("000000"), 1000); }

    public byte[] interfaceState() { return query(0x73, new byte[0], 1000); }

    public synchronized int dial() {
        for (int i = 0; i < 3; i++) {
            byte[] b = query(0x38, hex("000000"), 1000);
            if (b.length >= 2 && (b[0] & 0xFF) == 0x38) return b[1] & 0xFF;
            link.drain();
        }
        return -1;
    }

    public void setLights(int a, int b) { query(0x72, new byte[]{(byte) a, (byte) b}, 600); }

    public void clearButtons() { query(0x71, new byte[]{0x0F}, 600); }

    public byte[] unloadMat() { return query(0x84, hex("030000"), 3000); }

    public byte[] abort() { return query(0x68, new byte[0], 1000); }

    // --- NewAES handshake: derive the session key ourselves ---

    public byte[] handshake() { return handshake(7); }

    public synchronized byte[] handshake(int slot) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            // Sync the machine's frame parser (and flush our buffer) with a cheap read-only query
            // first; without this, the NewAES read picks up stray bytes, especially over Bluetooth.
            try { query(0x60, hex("000000"), 800); } catch (Exception ignored) {}
            link.drain();

            link.write(plainFrame(RESET_AES, randomBytes(3)));
            link.readReply(600);
            for (int i = 0; i < 6; i++) if (link.readReply(150).length == 0) break;
            link.drain();

            byte[] req = new byte[3 + 47];
            req[0] = 0x31; req[1] = (byte) NEWAES; req[2] = (byte) slot;
            System.arraycopy(randomBytes(47), 0, req, 3, 47);
            link.write(req);

            byte[] key2 = readNewAesResponse();
            if (key2 != null) { key = key2; return key; }
            last = new IllegalStateException("NewAES handshake did not complete");
        }
        throw last != null ? last : new IllegalStateException("handshake failed");
    }

    /** Read the 0x30 + 48-byte NewAES reply (may be preceded by stray bytes / split); null on timeout. */
    private byte[] readNewAesResponse() {
        byte[] acc = new byte[0];
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 2500) {
            byte[] chunk = link.readReply(400);
            if (chunk.length > 0) {
                byte[] merged = new byte[acc.length + chunk.length];
                System.arraycopy(acc, 0, merged, 0, acc.length);
                System.arraycopy(chunk, 0, merged, acc.length, chunk.length);
                acc = merged;
            }
            byte[] u = unescape(acc);
            int i = indexOf(u, (byte) 0x30);
            if (i >= 0 && u.length - i >= 49) {
                byte[] plain = aes(slice(u, i + 1, i + 49), kek, javax.crypto.Cipher.DECRYPT_MODE);
                if ((plain[0] & 0xFF) == 0x01) return slice(plain, 1, 33);
            }
        }
        return null;
    }

    // --- encrypted commands (require a session) ---

    public synchronized byte[] sendEnc(int op, byte[] payload, long waitMs) {
        if (key == null) throw new IllegalStateException("no session key; call handshake() first");
        link.write(encFrame(op, payload, key));
        byte[] u = unescape(link.readReply(waitMs));
        if (u.length == 0) return u;
        int n = bodyLen(u[0] & 0xFF);
        byte[] body = slice(u, 1, 1 + n);
        if ((u[0] & 0x80) != 0 && body.length % 16 == 0 && body.length > 0)
            return aes(body, key, javax.crypto.Cipher.DECRYPT_MODE);
        return body;
    }

    public byte[] sendEnc(int op, byte[] payload) { return sendEnc(op, payload, 1000); }

    private byte[] parseBody(byte[] raw) {
        if (raw.length == 0) return new byte[0];
        byte[] u = unescape(raw);
        int n = bodyLen(u[0] & 0xFF);
        return slice(u, 1, 1 + n);
    }

    @Override public synchronized void close() { link.close(); }

    private static int indexOf(byte[] b, byte v) {
        for (int i = 0; i < b.length; i++) if (b[i] == v) return i;
        return -1;
    }
}
