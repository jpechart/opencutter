package opencutter.job;

import opencutter.protocol.Protocol;
import opencutter.protocol.Session;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static opencutter.protocol.Protocol.hex;

/**
 * Drives one cut job: handshake -> (user loads mat) -> (user presses Go) -> paced draw -> done.
 * The UI supplies a listener for status/progress/log events and runs run()/finish() off the EDT.
 * finish() may be called from another thread at any time; it stops a running job first.
 *
 * Status byte (interfaceState body, length stripped): [0]=0x73 [1]=lights [2]=buttons [3]=0x03 [4]=progress [5]=dial.
 */
public final class CutController {

    public enum Status { DISCONNECTED, IDLE, WAITING_LOAD, WAITING_GO, CUTTING, DONE, STOPPED }

    public interface Listener {
        void onStatus(Status s);
        void onProgress(int done, int total);
        void onLog(String line);
    }

    private static final int L_MAT = 0x10, B_GO = 0x02;
    private static final int VECTORS_PER_FRAME = 13;     // 13 x 9 B + 2 B header = 119 -> one 128-byte block
    private static final int MAX_FRAME_ATTEMPTS = 300;

    // Design Space per-job setup (captured/decrypted). Pressure fills the last 2 bytes of SetMachineState.
    private static final byte[] P31 = hex("400000003ecccccd400000003ecccccd40400000421a666640400000421a6666");
    private static final byte[][] SPEED = {
        hex("00610000012c"), hex("00860000012c"), hex("005e00000096"), hex("008300000096")};

    private final Session s;
    private final Listener listener;
    private volatile boolean stop = false;
    private volatile Thread runner;

    public CutController(Session session, Listener listener) {
        this.s = session;
        this.listener = listener;
    }

    public int dial() { return s.dial(); }

    public boolean isRunning() { return runner != null; }

    public static byte[] setMachineState(int pressure) {
        byte[] b = hex("2800000d0c03e803e800");
        byte[] out = new byte[b.length + 2];
        System.arraycopy(b, 0, out, 0, b.length);
        out[b.length] = (byte) ((pressure >> 8) & 0xFF);
        out[b.length + 1] = (byte) (pressure & 0xFF);
        return out;
    }

    /** Pen-up MOVE for the first point of each polyline, pen-down LINE for the rest. */
    public static List<byte[]> vectorEntries(List<List<double[]>> polylines) {
        List<byte[]> entries = new ArrayList<>();
        for (List<double[]> pl : polylines) {
            for (int i = 0; i < pl.size(); i++) {
                byte[] e = new byte[9];
                e[0] = (byte) (i == 0 ? 2 : 0);   // MOVE / LINE
                putFloatLE(e, 1, (float) pl.get(i)[0]);
                putFloatLE(e, 5, (float) pl.get(i)[1]);
                entries.add(e);
            }
        }
        return entries;
    }

    /** Ask a running job to stop at the next safe point. Returns once the job thread has exited. */
    public void stop() {
        stop = true;
        Thread t = runner;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(5000); } catch (InterruptedException ignored) {}
        }
    }

    /** Light the blinking Load prompt and run the full sequence on this thread until DONE (or STOPPED). */
    public void run(List<List<double[]>> polylines, int pressure) {
        runner = Thread.currentThread();
        stop = false;
        try {
            s.clearButtons();
            s.setLights(0x46, 0x20);            // Load blinking
            listener.onStatus(Status.WAITING_LOAD);
            if (!waitFor(st -> (st.length > 1 && (st[1] & L_MAT) != 0))) return;
            sleep(1000);
            s.clearButtons();
            s.setLights(0x02, 0x08);            // Go blinking
            listener.onStatus(Status.WAITING_GO);
            if (!waitFor(st -> (st.length > 2 && (st[2] & B_GO) != 0))) return;
            s.clearButtons();
            s.setLights(0x02, 0x04);            // Go solid (running)
            listener.onStatus(Status.CUTTING);
            if (runCut(polylines, pressure)) listener.onStatus(Status.DONE);
        } finally {
            if (stop) listener.onStatus(Status.STOPPED);
            runner = null;
        }
    }

    /** @return true if the whole job was sent; false if stopped. Throws if the machine won't accept a frame. */
    private boolean runCut(List<List<double[]>> polylines, int pressure) {
        enc(0x22, hex("000000"), "EndDraw");
        enc(0x31, P31, "geometry 0x31");
        enc(0x30, setMachineState(pressure), "SetMachineState p=" + pressure);
        for (byte[] sp : SPEED) enc(0xA1, sp, "speed 0xA1");
        enc(0x21, hex("000000"), "BeginDraw");
        enc(0x22, hex("000000"), "EndDraw");
        enc(0x76, hex("0000003e00000080"), "OrientBlade");
        enc(0x21, hex("000000"), "BeginDraw");

        List<byte[]> entries = vectorEntries(polylines);
        List<byte[]> frames = chunk(entries);
        int n = frames.size(), lastFifo = -1;
        for (int i = 0; i < n && !stop; i++) {
            byte[] body = frames.get(i);
            int vecs = body.length / 9, need = vecs + 2;
            if (lastFifo >= 0 && lastFifo < need) sleep(Math.min(5000, (need - lastFifo) * 100L));
            boolean sent = false;
            for (int attempt = 0; attempt < MAX_FRAME_ATTEMPTS && !stop; attempt++) {
                byte[] r = s.sendEnc(0x43, body);
                boolean dropped = r.length > 1 && (r[1] & 0xFF) == 0x01;
                if (r.length > 5) lastFifo = r[5] & 0xFF;
                listener.onLog("MultiDrawVector " + (i + 1) + "/" + n + " (" + vecs + " vecs) "
                        + (dropped ? "DROPPED retry " + (attempt + 1) : "ok") + " fifo=" + lastFifo);
                if (!dropped) { sent = true; break; }
                int shortBy = need - Math.max(lastFifo, 0);
                sleep(Math.min(3000, Math.max(300, shortBy * 100L)));
            }
            if (stop) break;
            if (!sent)
                throw new IllegalStateException("machine kept dropping frame " + (i + 1) + "/" + n
                        + " after " + MAX_FRAME_ATTEMPTS + " attempts; cut aborted");
            listener.onProgress(i + 1, n);
        }
        enc(0x22, hex("000000"), "EndDraw");
        return !stop;
    }

    /** UI "Done" / "Stop": stop any running job, clear lights and unload. */
    public void finish() {
        stop();
        try { s.abort(); } catch (Exception ignored) {}
        s.setLights(0x46, 0x00);
        s.unloadMat();
        listener.onStatus(Status.IDLE);
    }

    private void enc(int op, byte[] payload, String label) {
        byte[] r = s.sendEnc(op, payload);
        listener.onLog(label + " -> " + Protocol.hex(r, 8));
    }

    /** Poll the interface state until cond holds. @return false if stopped first. */
    private boolean waitFor(java.util.function.Predicate<byte[]> cond) {
        while (!stop) {
            byte[] st = s.interfaceState();
            if (cond.test(st)) return true;
            sleep(150);
        }
        return false;
    }

    static List<byte[]> chunk(List<byte[]> entries) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += VECTORS_PER_FRAME) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (int j = i; j < Math.min(i + VECTORS_PER_FRAME, entries.size()); j++) b.writeBytes(entries.get(j));
            frames.add(b.toByteArray());
        }
        return frames;
    }

    private static void putFloatLE(byte[] b, int off, float f) {
        int bits = Float.floatToIntBits(f);
        b[off] = (byte) bits; b[off + 1] = (byte) (bits >> 8);
        b[off + 2] = (byte) (bits >> 16); b[off + 3] = (byte) (bits >> 24);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
