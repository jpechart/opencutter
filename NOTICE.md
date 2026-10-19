# Notices

## Interoperability statement

OpenCutter is independent software created to interoperate with a Cricut Explore Air 2 die-cutting
machine that its users own. It is **not affiliated with, authorized, or endorsed by Cricut, Inc.**
"Cricut", "Explore", "Design Space", and related marks belong to their owner and are used here only to
describe compatibility. No Cricut software is bundled, and none is required to run OpenCutter.

The communication protocol was understood by observing a machine the author owns, for the purpose of
making that hardware work with independent software. In the United States this kind of reverse
engineering for interoperability is generally recognized (e.g. 17 U.S.C. §1201(f)). This is not legal
advice.

## ⚠️ Machine key material

The Explore Air 2 authenticates motion commands with an AES session key. OpenCutter derives that key
from a 64-byte, per-machine-type value (the "base buffer") that originates inside Cricut's own software.

**This repository ships Option B below:** the Explore Air 2 base buffer is compiled into
`app/src/main/java/opencutter/protocol/Protocol.java` so the app works with no setup. That is a
deliberate choice by the maintainer for a tool built around one machine. If you fork or redistribute,
make your own call between the two options:

### Option A — Bring Your Own Key (lower legal exposure)

The published source contains **no** Cricut-derived key material. Each user supplies it once, extracted
from *their own* Design Space install, into a local file that is **gitignored** and never committed:

1. Remove the literal bytes from `BASE_BUFFERS` in
   `app/src/main/java/opencutter/protocol/Protocol.java` (leave the map empty or load from file).
2. Have the app read the buffer at startup from `config/keys.properties` (not committed), e.g.
   `helium2.base = <128 hex chars>`.
3. Document the one-time extraction for your users.

This keeps the public repository free of third-party key material.

### Option B — Bundle the key (plug-and-play, higher legal exposure) — *what this repo does*

Leave the base buffer compiled into `Protocol.java`. The app works with zero setup, but the published
build **redistributes key material extracted from Cricut's software**. Cricut has historically pursued
third-party tools over exactly this kind of thing. Understand and accept that risk for your
jurisdiction and use before you redistribute.

Whichever option you pick, never commit `config/keys.properties`, `config/secrets.ini`, extracted
`.dll`s, or protocol captures.

## Third-party components (bundled in the built jar)

| Component | Purpose | License |
|---|---|---|
| [jSerialComm](https://github.com/Fazecast/jSerialComm) | USB/serial I/O | Apache-2.0 / LGPL / GPL (multi) |
| [Apache PDFBox](https://pdfbox.apache.org/) | reading PDF cut geometry | Apache-2.0 |
| [JNA + jna-platform](https://github.com/java-native-access/jna) | Bluetooth (Winsock) calls | Apache-2.0 / LGPL-2.1 |

Each retains its own license; see the respective projects.
