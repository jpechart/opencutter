# OpenCutter

A small, self-contained Windows program that drives the **Cricut Explore Air 2** directly — load a
file, pick a material, cut. No Cricut Design Space, no account, no cloud, no subscription.

It talks to the machine over **USB or Bluetooth**, places your artwork **exactly where you drew it**
(the artboard is the sheet — nothing gets repositioned or rescaled), and runs entirely on your
computer.

> **Status:** works end-to-end on the Explore Air 2 (USB and Bluetooth), the one machine this was
> built against. Other Cricut models are untested and unsupported — see [Supported machines](#supported-machines).

---

## Why

Design Space re-crops designs to the objects, moves them around the mat, and rescales on import, so
carefully positioned layouts (e.g. a full sheet of labels aligned to printed artwork) come out in the
wrong place. OpenCutter treats your document's page as the physical sheet: a point on the page is cut
at that same point on the sheet. What you design is what it cuts.

## Features

- Direct control of the Explore Air 2 — **no Design Space required at runtime**
- **USB** and **Bluetooth** (the app makes the Bluetooth connection itself)
- Loads a **PDF** and cuts the paths on a layer named **`Cut Layer`** (or all paths if there's no such layer)
- Exact placement: the page *is* the sheet, in real inches
- Material + pressure presets matching the machine's dial (Paper − … Poster Board +, with Less/Default/More)
- Live FIFO flow control so large jobs don't drop commands
- One-window UI: pick machine → load file → START → load mat → Go → cut → Done

## Quick start (just run it)

If a packaged release is available:

1. Download `OpenCutter-<version>-win64.zip`.
2. Right-click → **Extract All**.
3. Open the `OpenCutter` folder and double-click **`OpenCutter.exe`**.
   - No Java needed — a runtime is bundled.
   - Windows SmartScreen may warn (the app is unsigned): **More info → Run anyway**.

See **[docs/USAGE.md](docs/USAGE.md)** for connecting and cutting. A neutral test file is included at
**[samples/sample-cut.pdf](samples/sample-cut.pdf)** (Letter-size, a `Cut Layer` with five shapes) so you
can verify your setup before using real material.

## Build it yourself

You need a **JDK 21+** and **Maven 3.9+**. Then:

```bash
cd app
mvn clean package
```

That runs the unit tests and produces `app/target/OpenCutter.jar` — a single runnable jar with all
dependencies inside:

```bash
java -jar target/OpenCutter.jar
```

To make the self-contained Windows app (bundled runtime, double-click `.exe`, no Java needed), see
**[docs/BUILDING.md](docs/BUILDING.md)**.

## Supported machines

| Model | Status |
|---|---|
| Explore Air 2 | ✅ supported — the machine this was developed and tested on |
| Explore / Explore Air / Explore One / Explore 3 | untested; same family, but they would need their own key material. No guarantees. |
| Maker / Maker 3 | not supported (different framing) |
| Joy | not supported |

OpenCutter lists any Cricut it finds as "Cricut (USB)" or by its Bluetooth name, and will try to talk
to it as an Explore Air 2. If it isn't one, expect the connect to fail. See
[docs/PROTOCOL.md](docs/PROTOCOL.md) for how the protocol works and what it would take to add a model.

## How it works (short version)

The machine speaks a small binary protocol over a serial link (USB CDC, or Bluetooth RFCOMM). Status,
dial and lights are plaintext; motion/cut commands run over an AES-256 session established by a
handshake. OpenCutter implements all of this in clean Java. Full writeup: **[docs/PROTOCOL.md](docs/PROTOCOL.md)**.

## Legal / interoperability

This is independent, interoperability software for hardware you own. It is not affiliated with or
endorsed by Cricut. The build includes the per-model key material the machine needs, so it works with
zero setup; **read [NOTICE.md](NOTICE.md)** for what that means before you redistribute it.

## License

[MIT](LICENSE) for OpenCutter's own code. See [NOTICE.md](NOTICE.md) for third-party components and the
interoperability notice.
