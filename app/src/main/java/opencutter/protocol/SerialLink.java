package opencutter.protocol;

import com.fazecast.jSerialComm.SerialPort;

import java.io.ByteArrayOutputStream;

/** USB (or any already-live COM port) transport via jSerialComm. */
public final class SerialLink implements Link {
    private final SerialPort port;

    public SerialLink(String portName) {
        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 300, 2000);
        if (!port.openPort()) throw new IllegalStateException("could not open " + portName);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        port.flushIOBuffers();
    }

    public void write(byte[] data) { port.writeBytes(data, data.length); }

    public byte[] readReply(long maxMillis) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        byte[] chunk = new byte[512];
        while (System.currentTimeMillis() - t0 < maxMillis) {
            int n = port.readBytes(chunk, chunk.length);
            if (n > 0) buf.write(chunk, 0, n);
            else if (buf.size() > 0) break;
        }
        return buf.toByteArray();
    }

    public byte[] readAtLeast(int need, long maxMillis) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        byte[] chunk = new byte[512];
        while (buf.size() < need && System.currentTimeMillis() - t0 < maxMillis) {
            int n = port.readBytes(chunk, chunk.length);
            if (n > 0) buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    public void drain() {
        byte[] chunk = new byte[512];
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 300) {
            if (port.readBytes(chunk, chunk.length) <= 0) break;
        }
        port.flushIOBuffers();
    }

    public void close() { port.closePort(); }
}
