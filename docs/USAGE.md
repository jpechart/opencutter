# Using OpenCutter

## 1. Connect the machine

Launch OpenCutter. The **Machine** list fills in as machines are found.

### USB (simplest)
Plug the Cricut into the PC with a USB cable and power it on. It appears as **"Cricut (USB)"** with
its COM port. Select it → **Connect**. Done.

### Bluetooth
First pair the Cricut to this PC **once**, through Windows:

1. Power on the Cricut.
2. Windows **Settings → Bluetooth & devices → Add device → Bluetooth**.
3. Pick **`CricutAIR2-XXXX`**. If asked for a PIN, enter **`0000`**.

After pairing, it shows in OpenCutter under its Bluetooth name, e.g. **"CricutAIR2-XXXX (Bluetooth)"**.
Select it → **Connect**.
The app establishes the Bluetooth link itself; a cold connect can take a few seconds (and may retry).

Once connected the button becomes **Disconnect**. If the machine is switched off or stops answering,
OpenCutter notices within a few seconds, disconnects, and tells you — power the Cricut back on and click
**Connect** again. There's no need to restart OpenCutter.

> Bluetooth tips: the machine must be **powered on and in range**. If a connect fails, just press
> Connect again. USB is the most reliable if you have a cable handy.

OpenCutter was built for the **Explore Air 2** and treats whatever it finds as one. Another Cricut model
will show up in the list but will most likely fail to connect — no guarantees.

## 2. Load a file

Click **Load file…** and choose a **PDF**.

- The **page is the sheet.** Lay your design out at the real sheet size (e.g. US Letter, 8.5 × 11 in)
  with everything positioned exactly where it should cut.
- OpenCutter cuts the paths on a layer named **`Cut Layer`**. If the PDF has no such layer, it cuts
  **everything** on the page, and says so under the file name. Only the **first page** is used.
- Stroked and filled paths are both cut as outlines. Compound paths (shapes with holes, text outlines)
  keep every contour. Clipping paths are ignored.
- Text is cut along the letter outlines, whether or not it was converted to paths. Invisible text
  (e.g. the hidden OCR layer of a scanned PDF) is ignored.
- Print-then-cut workflow: keep the printed artwork and the cut lines in the same document on separate
  layers (e.g. `Print Layer` and `Cut Layer`); print the artwork normally, then cut here.

The preview shows the sheet and exactly what will be cut, to scale — check it before you cut.

### Inkscape

Inkscape (checked with 1.4.2) does **not** save its layers into the PDF — there is no PDF layer option
in its save dialog — so OpenCutter sees an Inkscape PDF as one flat page and cuts everything on it.
Placement is exact (a 1 in square drawn at 1 in, 1 in cuts exactly there; mm documents work the same).

- **Cut-only designs** (shapes, lettering): just File → Save As → PDF and load it.
- **Print-then-cut**: save two PDFs from the same document — print one with everything visible, and for
  the cut PDF hide the artwork layer first so only the cut lines are on the page.

## 3. Cut

Click **START**:

- **Dial on Custom:** choose a **Material** (the full dial scale, `Paper −` … `Poster Board +`) and a
  **Pressure** (`Less` / `Default` / `More`). A live readout shows the resulting pressure value.
- **Dial on a material:** the dial sets the material; you only choose `Less` / `Default` / `More`.

Then follow the machine, exactly like Design Space:

1. The **Load** button blinks → put the sheet on the mat (align its top-left to the mat origin / your
   reference), press **Load** on the machine.
2. The **Go** button blinks → press **Go**.
3. It cuts; the progress bar fills as each batch completes. When the last line is cut the head returns
   home, then the status reads **Done**.
4. Click **Unload** → the mat ejects.

**Unload** is only clickable while a mat is actually in the machine (including one you loaded with the
machine's own Load button). To back out:

- at the Load prompt (no mat yet) click **Cancel** — the lights clear, nothing moves;
- waiting for Go (mat in, nothing cut yet) click **Stop / Unload** — the head returns home and the mat
  ejects;
- while cutting, the button is **Stop** and asks first — anything not cut yet is skipped. Note the
  progress bar reaches the end when the last lines are *sent*; the machine is still cutting them until
  the status reads **Done**.

## Alignment note

Cuts land ~0.25 in inside the mat's physical origin — a deliberate safe-travel margin (the carriage and
roller sit at the extreme edges). Place your sheet's top-left at the mat's grid origin; the preview
coordinates already account for the offset. If your first cut is slightly off versus printed artwork,
nudge the sheet, not the file.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Machine not in the list (USB) | Check the cable and that the machine is powered on; it enumerates as a USB serial device. |
| Machine not in the list (Bluetooth) | Pair it in Windows first (above). Only paired Cricuts appear. |
| Bluetooth connect fails | Make sure the machine is on and in range; press Connect again. Toggle the PC's Bluetooth off/on if the stack is wedged. |
| A cut stops partway | Flow control normally prevents this. If the machine keeps rejecting a frame the job aborts with a "kept dropping frame" error: click **Stop / Unload** (or **Unload**), check the USB cable / Bluetooth range, and re-run. |
| "No Cut Layer — cutting everything" | Expected for PDFs without layers (e.g. from Inkscape). Check the preview; see [Inkscape](#inkscape). |
| Power light turns red / machine stops responding | Switch the Cricut off and on. OpenCutter disconnects by itself; click **Connect** again. If it happened after several jobs in a row on an older version, update — older versions didn't return the head home between jobs. |
| Bluetooth won't reconnect after a crash | The machine holds the old link open for a while. Power-cycle the Cricut. (Closing OpenCutter normally releases the link.) |
| Cut is lightly cut / too deep | Adjust **Pressure** (Less/More), or pick a lighter/heavier material step. |
| SmartScreen warning on first run | The app is unsigned: **More info → Run anyway**. |
