package opencutter.protocol;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;

import static opencutter.protocol.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    private static final byte[] KEY = randomBytes(32);

    @Test void checksumIsLowByteOfSum() {
        assertEquals(0x00, checksum(new byte[0], 0));
        assertEquals(0xFF, checksum(hex("ff"), 1));
        assertEquals(0x01, checksum(hex("ff02"), 2));     // 0x101 wraps
        assertEquals(0x03, checksum(hex("010203"), 2));   // honours len
    }

    @Test void plainFrameLengthCountsOpAndPayload() {
        assertArrayEquals(hex("04 60 00 00 00"), plainFrame(0x60, hex("000000")));
        assertArrayEquals(hex("01 73"), plainFrame(0x73, new byte[0]));
    }

    @Test void encFramePadsToBlockWithChecksumLast() {
        byte[] f = encFrame(0x22, hex("000000"), KEY);     // core = 5 bytes -> 16
        assertEquals(17, f.length);
        assertEquals((byte) 0x90, f[0]);                   // 0x80 | 16
        byte[] inner = aes(slice(f, 1, 17), KEY, Cipher.DECRYPT_MODE);
        assertArrayEquals(hex("04 22 00 00 00"), slice(inner, 0, 5));
        for (int i = 5; i < 15; i++) assertEquals(0, inner[i]);
        assertEquals((byte) checksum(hex("0422000000"), 5), inner[15]);
    }

    @Test void encFrameExactMultipleStillLeavesRoomForChecksum() {
        byte[] f = encFrame(0x43, new byte[14], KEY);      // core = 16 -> must pad to 32
        assertEquals(33, f.length);
        assertEquals((byte) 0xA0, f[0]);
    }

    @Test void encFrame128ByteBodyWrapsHeaderTo0x80() {
        byte[] f = encFrame(0x43, new byte[13 * 9], KEY);  // core = 119 -> 128
        assertEquals(129, f.length);
        assertEquals((byte) 0x80, f[0]);
        assertEquals(128, bodyLen(f[0] & 0xFF));
    }

    @Test void bodyLenPlainAndEncrypted() {
        assertEquals(9, bodyLen(0x09));
        assertEquals(16, bodyLen(0x90));
        assertEquals(128, bodyLen(0x80));
    }

    @Test void unescapeUndoesByteStuffing() {
        assertArrayEquals(hex("24"), unescape(hex("5ea2")));
        assertArrayEquals(hex("0124ff5e"), unescape(hex("015ea2ff5e")));   // trailing lone 0x5E kept
        assertArrayEquals(hex("5e01"), unescape(hex("5e01")));
    }

    @Test void kekTakesOddBytesReversed() {
        byte[] base = new byte[64];
        for (int i = 0; i < 64; i++) base[i] = (byte) i;
        byte[] kek = kekFromBase(base);
        assertEquals(32, kek.length);
        assertEquals(63, kek[0]);
        assertEquals(61, kek[1]);
        assertEquals(1, kek[31]);
        assertThrows(IllegalArgumentException.class, () -> kekFromBase(new byte[63]));
    }

    @Test void bundledKeyDerives() {
        assertEquals(32, kekForType(10).length);
        assertThrows(IllegalArgumentException.class, () -> kekForType(99));
    }

    @Test void hexRoundTrip() {
        assertEquals("de ad be ef", hex(hex("DE AD be ef")));
        assertEquals("de ad", hex(hex("deadbeef"), 2));
    }
}
