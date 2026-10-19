# Explore Air 2 protocol notes

How OpenCutter talks to the machine. This is interoperability documentation, written from observing a
machine the author owns. Addresses/offsets are implementation detail; the shapes below are what you
need to drive the hardware.

## Transport

- **USB:** the machine is a USB CDC serial device (VID `0x20D3`), 115200 baud. Opens like any COM port.
- **Bluetooth:** RFCOMM / SPP on **channel 6**. On Windows the paired machine's outgoing COM port is a
  *phantom* — it doesn't exist until an RFCOMM link is up — so OpenCutter connects a raw RFCOMM socket
  to the machine's MAC on channel 6 (via Winsock `AF_BTH`), rather than opening the COM port.
- The byte stream is identical on both transports.

## Framing

Plaintext frame (status, dial, lights, load/unload — work with no session):

```
[len] [op] [payload...]          len = number of bytes after len (op + payload); NO checksum
```

Encrypted frame (motion/cut — require a session):

```
[0x80 | encLen7] [ AES-256-ECB( inner ) ]
inner = [len] [op] [payload]  zero-padded to the next 16-byte multiple,
        with the 8-bit checksum of (len+op+payload) placed in the LAST byte of the block
encLen = encrypted byte count (16, 32, … ; 128 wraps to 0x00 so 0x80 means a 128-byte body)
```

Replies: `[hdr] [body]`; `hdr & 0x80` means the body is encrypted. The byte pair `0x5E 0xA2` in a reply
un-stuffs to a literal `0x24` (undo before parsing/decrypting).

## Session handshake (NewAES)

Motion commands only run after establishing an AES-256 session:

1. `ResetAES` (plaintext op `0xCF`).
2. `NewAES`: send `0x31 0xC7 <slot:2-15> <47 random bytes>`.
3. Machine replies `0x30` + **48 bytes**. Those 48 bytes are the session key, **AES-256-ECB encrypted**
   by the machine with a per-machine **key-encryption key (KEK)**.
4. `sessionKey = AES_ECB_decrypt(resp48, KEK)[1:33]` (the leading byte is `0x01`).
5. Thereafter, set the `0x80` flag and AES-encrypt each frame body with the session key.

Over Bluetooth the handshake is timing-sensitive: sync with a plaintext status read first, drain, then
`ResetAES` + `NewAES`, and scan the accumulated reply for `0x30`+48 (retry a few times). See
`Session.handshake()`.

### The KEK

The KEK is **constant per machine type** (all Explore Air 2 units share one). It is derived from a
64-byte "base buffer" for that type:

```
KEK[i] = base[63 - 2*i]      // the odd-indexed bytes of the base buffer, reversed (32 bytes)
```

The base buffer originates in Cricut's own software (it is not computed by us). How it is provided to
the build is a distribution decision — see [../NOTICE.md](../NOTICE.md). The derivation above is all
that is required once you have the buffer for your machine type.

## Commands (subset used)

Plaintext / read-only:

| op | meaning | notes |
|----|---------|-------|
| `0x60` | GetMachineStatus | `04 60 00 00 00` → `09 60 6a …` |
| `0x73` | GetInterfaceState | lights/buttons/progress/dial |
| `0x38` | GetMaterialDial | value 1 = Custom, 2..16 = the 15 stops |
| `0x72 a b` | set lights | Load solid `06 20`, Go blink `02 08`, Go solid `02 04`, all off `46 00`, Unload `46 24` |
| `0x71 0f` | clear button-press bits | |
| `0x84 03` | unload mat | |
| `0x68` | abort / finish | |

Status reply body (len stripped): `[0x73][lights][buttons][0x03][progress][dial]`.
Lights bits: `0x20` Load, `0x10` mat loaded, `0x08` Go-blink, `0x04` Go-solid. Buttons: `0x04` Load, `0x02` Go.

Encrypted cut sequence (per job): `EndDraw 0x22`, geometry `0x31`, `SetMachineState 0x30`
(pressure = last 2 bytes, big-endian), speed registers `0xA1` ×4, `BeginDraw 0x21`, `OrientBlade 0x76`,
then `MultiDrawVector 0x43` frames, `EndDraw 0x22`.

`MultiDrawVector` entries are 9 bytes each: `[type][x float32 LE][y float32 LE]`, coordinates in
**inches**, `type` 2 = move (blade up), 0 = line (blade down). Keep ≤ 13 vectors per frame (128-byte
cap) and pace to the FIFO: each ACK's byte[5] is free space; a drop NAK starts `0x01 … 0x3c` — wait and
re-send that frame.

### Coordinates

The page is the sheet. Machine inches = PDF points / 72 + a mat-origin offset of **(0.25, 0.25) in**
(a deliberate safe-travel margin; keep it).

## Do NOT send

Firmware/bootloader and persistent-memory writes can brick or mis-calibrate the machine. Avoid:
`0x04` HIDErase, `0x05` HIDProgram, `0x08` HIDReset, `0x12` CheckFirmware, `0xA1`-to-EEPROM commit
(`0xA3` CommitPersistentValue), `0xFF` SendZeros. Read firmware via GetMachineStatus (`0x60`).

## Adding another model

Other Explore-family machines likely share this framing but need their own type's base buffer and
possibly different opcodes; Maker uses a 2-byte length header. Identify the machine type, obtain its
base buffer, and add it alongside the Air 2 entry.
