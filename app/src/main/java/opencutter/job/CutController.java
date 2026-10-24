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

    public enum Status { DISCONNECTED, IDLE, WAITING_LOAD, WAITING_GO, CUTTING, FINISHING, DONE, STOPPED }

    public interface Listener {
        void onStatus(Status s);
        void onProgress(int done, int total);
        void onLog(String line);
    }

    private static final int L_MAT = 0x10, B_GO = 0x02;
    private static final int VECTORS_PER_FRAME = 13;     // 13 x 9 B + 2 B header = 119 -> one 128-byte block
    private static final int MAX_FRAME_ATTEMPTS = 300;
    /** End-of-job carriage park, machine inches (Design Space: two pen-up moves to 4.0, 0). */
    static final double[] PARK = {4.0, 0.0};

    // Design Space per-job setup (captured/decrypted). Pressure fills the last 2 bytes of SetMachineState.
    private static final byte[] P31 = hex("400000003ecccccd400000003ecccccd40400000421a666640400000421a6666");
    private static final byte[][] SPEED = {
        hex("00610000012c"), hex("00860000012c"), hex("005e00000096"), hex("008300000096")};

    private final Session s;
    private final Listener listener;
    private volatile boolean stop = false;
    private volatile Thread runner;
    private volatile boolean matIn = false;    // we saw a mat load in this job and haven't ejected it
    private volatile boolean parked = false;   // the last job ended with the head confirmed at PARK

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

    /** The end-of-job park frame: two pen-up MOVEs to {@link #PARK}. */
    static byte[] parkFrame() {
        byte[] b = new byte[18];
        for (int off = 0; off < 18; off += 9) {
            b[off] = 2;                                   // MOVE
            putFloatLE(b, off + 1, (float) PARK[0]);
            putFloatLE(b, off + 5, (float) PARK[1]);
        }
        return b;
    }

    /** Ask a running job to stop at the next safe point. Returns once the job thread has exited. */
    public void stop() {
        stop = true;
        Thread t = runner;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(5000); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Is a mat in? TRUE/FALSE, or null if the machine didn't answer (link lost / powered off).
     * A job that died mid-draw can make the machine report no mat while one is physically in, so a mat
     * this controller saw load (and hasn't ejected) counts as loaded regardless.
     */
    public Boolean matLoaded() {
        byte[] st = s.interfaceState();
        if (st.length < 2 || (st[0] & 0xFF) != 0x73) return null;
        return matIn || (st[1] & L_MAT) != 0;
    }

    /** Light the blinking Load prompt and run the full sequence on this thread until DONE (or STOPPED). */
    public void run(List<List<double[]>> polylines, int pressure) {
        runner = Thread.currentThread();
        stop = false;
        parked = false;
        try {
            s.clearButtons();
            s.setLights(0x46, 0x20);            // Load blinking
            listener.onStatus(Status.WAITING_LOAD);
            if (!waitFor(st -> (st.length > 1 && (st[1] & L_MAT) != 0))) return;
            matIn = true;
            sleep(1000);
            s.clearButtons();
            s.setLights(0x02, 0x08);            // Go blinking
            listener.onStatus(Status.WAITING_GO);
            if (!waitFor(st -> (st.length > 2 && (st[2] & B_GO) != 0))) return;
            s.clearButtons();
            s.setLights(0x02, 0x04);            // Go solid (running)
            logPosition("carriage before cut");
            s.abort();                          // reset cut state before drawing, as Design Space does
            s.setLights(0x01, 0x01);
            listener.onStatus(Status.CUTTING);
            if (runCut(polylines, pressure)) {
                // The last frames are still queued in the machine; DONE once the head is actually home.
                listener.onStatus(Status.FINISHING);
                parked = waitParked(180_000);
                if (!stop) listener.onStatus(Status.DONE);
            }
        } finally {
            if (stop) listener.onStatus(Status.STOPPED);
            runner = null;
        }
    }

    /** @return true if the whole job was sent; false if stopped. Throws if the machine won't accept a frame. */
    private boolean runCut(List<List<double[]>> polylines, int pressure) {
        enc(0x22, hex("000000"), "EndDraw");
        enc(0x30, setMachineState(pressure), "SetMachineState p=" + pressure);
        enc(0x31, P31, "geometry 0x31");
        for (byte[] sp : SPEED) enc(0xA1, sp, "speed 0xA1");
        enc(0x21, hex("000000"), "BeginDraw");
        enc(0x22, hex("000000"), "EndDraw");
        enc(0x76, hex("0000003e00000080"), "OrientBlade", 3000);   // takes ~1.2 s; a late reply would shift every later ACK
        enc(0x21, hex("000000"), "BeginDraw");

        List<byte[]> entries = vectorEntries(polylines);
        List<byte[]> frames = chunk(entries);
        int n = frames.size(), lastFifo = -1;
        for (int i = 0; i < n && !stop; i++) {
            byte[] body = frames.get(i);
            int vecs = body.length / 9, need = vecs + 2;
            if (lastFifo >= 0 && lastFifo < need) sleep(Math.min(5000, (need - lastFifo) * 100L));
            lastFifo = sendFrame(body, "MultiDrawVector " + (i + 1) + "/" + n, lastFifo);
            if (stop) break;
            listener.onProgress(i + 1, n);
        }
        enc(0x22, hex("000000"), "EndDraw");
        if (stop) return false;

        // Park the carriage in its own draw section, exactly as Design Space ends every job. Without
        // this the head is left at the last cut point and repeated jobs creep out of bounds (issue #3).
        sendPark(lastFifo);
        return !stop;
    }

    private void sendPark(int lastFifo) {
        enc(0x21, hex("000000"), "BeginDraw");
        sendFrame(parkFrame(), "park", lastFifo);
        enc(0x22, hex("000000"), "EndDraw");
    }

    /**
     * Poll ReadPositionFloat until the carriage reaches {@link #PARK}. Gives up early if the carriage stops
     * somewhere else, and falls back to a fixed wait if the machine doesn't report its position.
     * @return true if the head was seen at the park position.
     */
    private boolean waitParked(long timeoutMs) {
        long t0 = System.currentTimeMillis(), stableSince = t0;
        double[] last = null;
        while (!stop && System.currentTimeMillis() - t0 < timeoutMs) {
            double[] p = s.position();
            long now = System.currentTimeMillis();
            if (p == null) {
                if (last == null && now - t0 > 2000) {
                    listener.onLog("carriage position not readable; waiting a fixed 3 s");
                    sleep(3000);
                    return false;
                }
            } else if (Math.abs(p[0] - PARK[0]) < 0.05 && Math.abs(p[1] - PARK[1]) < 0.1) {
                // y (the roller axis) settles a little short of 0 on real hardware, e.g. 0.066
                listener.onLog(String.format("carriage parked at (%.3f, %.3f)", p[0], p[1]));
                return true;
            } else if (last == null || Math.abs(p[0] - last[0]) > 0.001 || Math.abs(p[1] - last[1]) > 0.001) {
                last = p; stableSince = now;
            } else if (now - stableSince > 3000) {
                listener.onLog(String.format("carriage stopped at (%.3f, %.3f), not at park (%.1f, %.1f)",
                        p[0], p[1], PARK[0], PARK[1]));
                return false;
            }
            sleep(250);
        }
        return false;
    }

    private void logPosition(String label) {
        double[] p = s.position();
        if (p != null) listener.onLog(String.format("%s: (%.3f, %.3f)", label, p[0], p[1]));
    }

    /** Send one MultiDrawVector frame, re-sending while the machine drops it. @return the last free-FIFO count. */
    private int sendFrame(byte[] body, String label, int lastFifo) {
        int need = body.length / 9 + 2;
        for (int attempt = 0; attempt < MAX_FRAME_ATTEMPTS && !stop; attempt++) {
            byte[] r = s.sendEnc(0x43, body);
            boolean dropped = r.length > 1 && (r[1] & 0xFF) == 0x01;
            if (r.length > 5) lastFifo = r[5] & 0xFF;
            listener.onLog(label + " (" + body.length / 9 + " vecs) "
                    + (dropped ? "DROPPED retry " + (attempt + 1) : "ok") + " fifo=" + lastFifo);
            if (!dropped) return lastFifo;
            int shortBy = need - Math.max(lastFifo, 0);
            sleep(Math.min(3000, Math.max(300, shortBy * 100L)));
        }
        if (stop) return lastFifo;
        throw new IllegalStateException("machine kept dropping frame '" + label + "' after "
                + MAX_FRAME_ATTEMPTS + " attempts; cut aborted");
    }

    /**
     * UI "Unload" / "Stop" / "Cancel": stop any running job, send the head home, clear lights and eject
     * the mat. With no mat in (cancelled at the Load prompt) it only clears the lights.
     */
    public void finish() {
        boolean wasParked = parked;
        stop();
        boolean jobGone = runner == null;
        if (jobGone) stop = false;                       // let the park below run
        boolean loaded = matIn;                          // no answer from the machine: trust what we saw
        try { Boolean m = matLoaded(); if (m != null) loaded = m; } catch (Exception ignored) {}
        try { s.abort(); } catch (Exception ignored) {}  // flush anything an interrupted job left queued
        if (loaded && !wasParked && jobGone) {
            sendPark(-1);
            waitParked(15_000);
            try { s.abort(); } catch (Exception ignored) {}
        }
        s.setLights(0x46, 0x00);
        if (loaded) listener.onLog("unload mat -> " + Protocol.hex(s.unloadMat(), 8));
        else listener.onLog("no mat in; nothing to eject");
        matIn = false;
        parked = false;
        listener.onStatus(Status.IDLE);
    }

    private void enc(int op, byte[] payload, String label) { enc(op, payload, label, 1000); }

    /**
     * Send one encrypted command. If the machine's queue is full it answers {@code <len> 01 <op> …}
     * ("dropped") — seen for BeginDraw right after a long cut — so re-send until it's accepted.
     */
    private void enc(int op, byte[] payload, String label, long waitMs) {
        for (int attempt = 1; ; attempt++) {
            byte[] r = s.sendEnc(op, payload, waitMs);
            boolean dropped = r.length > 2 && (r[1] & 0xFF) == 0x01 && (r[2] & 0xFF) == op;
            listener.onLog(label + " -> " + Protocol.hex(r, 8) + (dropped ? "  DROPPED retry " + attempt : ""));
            if (!dropped || stop) return;
            if (attempt >= MAX_FRAME_ATTEMPTS)
                throw new IllegalStateException("machine kept dropping " + label + "; cut aborted");
            sleep(500);
        }
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
