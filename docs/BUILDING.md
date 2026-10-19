# Building OpenCutter

## Prerequisites

- **JDK 21 or newer** (built and tested on JDK 25). Verify: `java -version`
- **Maven 3.9+**. Verify: `mvn -v`
- Windows (the app targets Windows; the Bluetooth transport uses Winsock)

No other setup — jSerialComm, PDFBox and JNA are pulled by Maven and bundled into the output jar.

## 1. Build the runnable jar

```bash
cd app
mvn clean package
```

This runs the unit tests (`mvn test` runs them alone) and outputs **`app/target/OpenCutter.jar`** —
one fat jar (maven-shade-plugin merges all dependencies in, with the main class set). Run it anywhere
a JDK 21+ is present:

```bash
java -jar app/target/OpenCutter.jar
```

## 2. (Optional) Self-contained Windows app — no Java needed on the target PC

Use the JDK's built-in `jpackage` to wrap the jar plus a trimmed runtime into a double-click `.exe`:

```bash
# from the repo root, after step 1
mkdir -p build-stage dist
cp app/target/OpenCutter.jar build-stage/
jpackage \
  --type app-image \
  --name OpenCutter \
  --input build-stage \
  --main-jar OpenCutter.jar \
  --main-class opencutter.ui.App \
  --dest dist \
  --app-version 0.1.0 \
  --vendor "OpenCutter"
```

Result: `dist/OpenCutter/OpenCutter.exe` (with a bundled `runtime/`). Zip the `dist/OpenCutter` folder
and that's your portable release — the target machine needs **no** Java install.

To make an installer instead of a folder, use `--type exe` or `--type msi` (the latter needs
[WiX Toolset](https://wixtoolset.org/) installed).

## 3. (Alternative) launch4j exe wrapper

If you prefer a thin `.exe` that launches the jar against a system JRE, point
[launch4j](https://launch4j.sourceforge.net/) at `app/target/OpenCutter.jar` with main class
`opencutter.ui.App`. (This path requires Java on the target PC.)

## CI

`.github/workflows/build.yml` runs `mvn package` on `windows-latest` for every push and PR and uploads
`OpenCutter.jar` as a build artifact.

## Notes

- **Key material:** the Explore Air 2 key material is compiled in (Option B in
  [../NOTICE.md](../NOTICE.md)). Read that before redistributing a build.
- **jSerialComm native:** the app extracts the correct Windows x86_64 serial native from the jar to
  `~/.opencutter/native` at startup (jSerialComm 2.11 mis-detects arch on some x64 boxes; this
  sidesteps it). No action needed.
- **Code signing:** the packaged `.exe` is unsigned, so Windows SmartScreen warns on first run. Sign
  with your own certificate (`signtool`) to remove the warning for distribution.
