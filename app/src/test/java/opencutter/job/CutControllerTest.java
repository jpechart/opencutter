package opencutter.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static opencutter.protocol.Protocol.hex;
import static org.junit.jupiter.api.Assertions.*;

class CutControllerTest {

    @Test void setMachineStatePutsPressureBigEndianLast() {
        byte[] b = CutController.setMachineState(0x0123);
        assertEquals(12, b.length);
        assertEquals(0x01, b[10]);
        assertEquals(0x23, b[11]);
        assertArrayEquals(hex("2800000d0c03e803e800"), java.util.Arrays.copyOf(b, 10));
    }

    @Test void vectorEntriesMoveThenLines() {
        List<byte[]> e = CutController.vectorEntries(List.of(
                List.of(new double[]{1, 2}, new double[]{3, 4}),
                List.of(new double[]{5, 6})));
        assertEquals(3, e.size());
        assertEquals(2, e.get(0)[0]);  // MOVE
        assertEquals(0, e.get(1)[0]);  // LINE
        assertEquals(2, e.get(2)[0]);  // MOVE (new polyline)
        assertArrayEquals(hex("0000803f"), java.util.Arrays.copyOfRange(e.get(0), 1, 5)); // 1.0f LE
        assertArrayEquals(hex("00000040"), java.util.Arrays.copyOfRange(e.get(0), 5, 9)); // 2.0f LE
    }

    /** Issue #3: byte-identical to the park frame Design Space sends after every job. */
    @Test void parkFrameMatchesDesignSpace() {
        assertArrayEquals(hex("020000804000000000" + "020000804000000000"), CutController.parkFrame());
    }

    @Test void chunkNeverExceedsOneEncryptedBlock() {
        List<byte[]> entries = CutController.vectorEntries(List.of(
                java.util.Collections.nCopies(30, new double[]{0, 0})));
        List<byte[]> frames = CutController.chunk(entries);
        assertEquals(3, frames.size());
        assertEquals(13 * 9, frames.get(0).length);
        assertEquals(4 * 9, frames.get(2).length);
        for (byte[] f : frames) assertTrue(2 + f.length <= 127, "core must fit one 128-byte block with checksum");
    }
}
