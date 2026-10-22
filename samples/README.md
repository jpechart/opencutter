# Sample files

**`sample-cut.pdf`** — a neutral test file: a US Letter (8.5 × 11 in) page with a layer named
**`Cut Layer`** containing five simple shapes (squares and circles). Use it to verify your setup
end-to-end without wasting good material.

Suggested first run: load plain paper (or just an empty mat with the pen tool if you have one), pick a
light material/pressure, and cut. The shapes should land centered on the sheet.

It also shows the expected document structure: lay your design out at the real sheet size and put the
lines you want cut on a layer called `Cut Layer`. (If a PDF has no such layer, OpenCutter cuts every
vector path on the page.)

**`sample-print-and-cut.pdf`** — a US Letter page with **two** layers, `Print Layer` and `Cut Layer`,
demonstrating the print-then-cut workflow: print the document normally (the artwork), then cut here —
OpenCutter only cuts the `Cut Layer`, so the printed design and the cut lines stay aligned.
