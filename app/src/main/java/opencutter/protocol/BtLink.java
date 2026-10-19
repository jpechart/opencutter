package opencutter.protocol;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Library;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Bluetooth RFCOMM transport via Winsock (AF_BTH), called through JNA. This replicates the Python
 * socket that reliably drives the Cricut over Bluetooth: connect to the device's MAC on RFCOMM
 * channel 6 (the "hello" that brings the link up), then stream the same bytes as USB.
 *
 * Windows-only; the app falls back to USB elsewhere.
 */
public final class BtLink implements Link {

    private static final int AF_BTH = 32;
    private static final int SOCK_STREAM = 1;
    private static final int BTHPROTO_RFCOMM = 3;
    private static final int SOL_SOCKET = 0xFFFF;
    private static final int SO_RCVTIMEO = 0x1006;
    private static final long INVALID_SOCKET = -1L;
    private static final int SOCKET_ERROR = -1;

    public interface Ws2 extends Library {
        Ws2 INSTANCE = Native.load("Ws2_32", Ws2.class);
        int WSAStartup(short version, Pointer wsaData);
        int WSACleanup();
        long socket(int af, int type, int protocol);
        int connect(long s, Pointer name, int namelen);
        int send(long s, byte[] buf, int len, int flags);
        int recv(long s, byte[] buf, int len, int flags);
        int closesocket(long s);
        int WSAGetLastError();
        int setsockopt(long s, int level, int optname, Pointer optval, int optlen);
    }

    /** SOCKADDR_BTH, packed (pack=1, size 30): family(0,2) btAddr(2,8) serviceClassId(10,16) port(26,4). */
    public static final class SockaddrBth extends Structure {
        public short addressFamily;
        public long btAddr;
        public byte[] serviceClassId = new byte[16];
        public int port;
        public SockaddrBth() { setAlignType(ALIGN_NONE); }
        @Override protected List<String> getFieldOrder() {
            return List.of("addressFamily", "btAddr", "serviceClassId", "port");
        }
    }

    private static boolean started = false;
    private long sock = INVALID_SOCKET;

    public BtLink(String mac, int channel) {
        synchronized (BtLink.class) {
            if (!started) {
                Memory wsa = new Memory(512);
                int r = Ws2.INSTANCE.WSAStartup((short) 0x0202, wsa);
                if (r != 0) throw new IllegalStateException("WSAStartup failed: " + r);
                started = true;
            }
        }
        sock = Ws2.INSTANCE.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
        if (sock == INVALID_SOCKET)
            throw new IllegalStateException("Bluetooth socket() failed: WSA " + Ws2.INSTANCE.WSAGetLastError());

        SockaddrBth addr = new SockaddrBth();
        addr.addressFamily = (short) AF_BTH;
        addr.btAddr = parseMac(mac);
        addr.port = channel;
        addr.write();
        long s = sock;
        int rc = Ws2.INSTANCE.connect(s, addr.getPointer(), addr.size());
        int err = Native.getLastError();            // JNA captures the real WSA error per call
        if (rc == SOCKET_ERROR) {
            Ws2.INSTANCE.closesocket(s);
            sock = INVALID_SOCKET;
            throw new IllegalStateException("Bluetooth connect failed (WSA " + err + ") — "
                    + "is the machine powered on and in range?");
        }
        setRecvTimeout(300);
    }

    private void setRecvTimeout(int ms) {
        Memory v = new Memory(4);
        v.setInt(0, ms);
        Ws2.INSTANCE.setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, v, 4);
    }

    /** MAC "E8:EB:1B:77:6D:7A" or "E8EB1B776D7A" -> BTH_ADDR (48-bit int, first octet most significant). */
    public static long parseMac(String mac) {
        return Long.parseLong(mac.replace(":", "").replace("-", ""), 16);
    }

    public void write(byte[] data) {
        int sent = Ws2.INSTANCE.send(sock, data, data.length, 0);
        if (sent == SOCKET_ERROR) throw new IllegalStateException("BT send failed (WSA " + Native.getLastError() + ")");
    }

    public byte[] readReply(long maxMillis) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        byte[] chunk = new byte[512];
        while (System.currentTimeMillis() - t0 < maxMillis) {
            int n = Ws2.INSTANCE.recv(sock, chunk, chunk.length, 0);
            if (n > 0) buf.write(chunk, 0, n);
            else if (buf.size() > 0) break;        // got something then a gap -> frame complete
            else if (n == 0) break;                // peer closed
        }
        return buf.toByteArray();
    }

    public byte[] readAtLeast(int need, long maxMillis) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        byte[] chunk = new byte[512];
        while (buf.size() < need && System.currentTimeMillis() - t0 < maxMillis) {
            int n = Ws2.INSTANCE.recv(sock, chunk, chunk.length, 0);
            if (n > 0) buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    public void drain() {
        long t0 = System.currentTimeMillis();
        byte[] chunk = new byte[512];
        while (System.currentTimeMillis() - t0 < 300) {
            if (Ws2.INSTANCE.recv(sock, chunk, chunk.length, 0) <= 0) break;
        }
    }

    public void close() {
        if (sock != INVALID_SOCKET) { Ws2.INSTANCE.closesocket(sock); sock = INVALID_SOCKET; }
    }
}
