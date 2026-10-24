package opencutter.importer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Extracts cut geometry from a PDF, restricted to a named optional-content layer ("Cut Layer").
 * The artboard IS the sheet: a point on the page is cut at that point on the sheet. Output is in
 * machine inches (top-left origin, Y down) with the mat-origin offset applied.
 *
 * Path semantics follow PDF: a path may contain several subpaths (compound paths, text outlines),
 * and it only becomes a cut when a painting operator (S, f, B, …) consumes it. Paths that end in
 * {@code n} (clipping only) are discarded. Live (unconverted) text is cut along its glyph outlines;
 * invisible text is skipped.
 */
public final class CutImporter extends PDFGraphicsStreamEngine {

    public static final double PT = 1.0 / 72.0;
    public static final String DEFAULT_LAYER = "Cut Layer";
    public static final double DEFAULT_OFFSET_IN = 0.25;

    /** Max chord deviation when flattening curves, in PDF points (~0.1 mm). */
    private static final double FLATTEN_TOL_PT = 0.3;
    /** Glyph contours smaller than this in both directions (inches) are dropped. */
    private static final double MIN_CONTOUR_IN = 0.005;

    public static final class Result {
        public final List<List<double[]>> polylines;  // each: list of {x,y} in machine inches
        public final double sheetWin, sheetHin;
        public final double offX, offY;                // mat-origin offset already applied, inches
        public final double[] bbox;                    // x0,y0,x1,y1 machine inches
        public final int pageCount;
        public final String layer;                     // layer actually cut; null = all paths (no such layer)
        Result(List<List<double[]>> p, double w, double h, double ox, double oy, double[] b, int pages, String layer) {
            polylines = p; sheetWin = w; sheetHin = h; offX = ox; offY = oy; bbox = b; pageCount = pages;
            this.layer = layer;
        }
        public int pointCount() { int n = 0; for (List<double[]> pl : polylines) n += pl.size(); return n; }
    }

    private final String layerName;      // null = all layers
    private final double offX, offY, pageH;
    private final List<List<double[]>> out = new ArrayList<>();
    private final Deque<Boolean> ocStack = new ArrayDeque<>();   // true = inside the target layer
    private final List<List<double[]>> pending = new ArrayList<>();  // finished subpaths of the path being built
    private List<double[]> current = new ArrayList<>();
    private double curX, curY, startX, startY;

    private CutImporter(PDPage page, String layerName, double offX, double offY) {
        super(page);
        this.layerName = layerName;
        this.offX = offX; this.offY = offY;
        this.pageH = page.getMediaBox().getHeight();
    }

    /**
     * Cuts the paths on {@code layer}. If the PDF has no layer by that name (e.g. Inkscape exports, which
     * don't write layers), every vector path on the page is cut instead; {@link Result#layer} is then null.
     */
    public static Result importCuts(String path, String layer, double offX, double offY) throws IOException {
        try (PDDocument doc = Loader.loadPDF(new File(path))) {
            if (layer != null && !hasLayer(doc, layer)) layer = null;
            PDPage page = doc.getPage(0);
            CutImporter imp = new CutImporter(page, layer, offX, offY);
            imp.processPage(page);
            if (imp.out.isEmpty())
                throw new IOException("no cut geometry found" + (layer != null ? " on layer '" + layer + "'" : ""));
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (List<double[]> pl : imp.out) for (double[] pt : pl) {
                x0 = Math.min(x0, pt[0]); y0 = Math.min(y0, pt[1]);
                x1 = Math.max(x1, pt[0]); y1 = Math.max(y1, pt[1]);
            }
            double w = page.getMediaBox().getWidth() * PT, h = page.getMediaBox().getHeight() * PT;
            return new Result(imp.out, w, h, offX, offY, new double[]{x0, y0, x1, y1}, doc.getNumberOfPages(), layer);
        }
    }

    private static boolean hasLayer(PDDocument doc, String name) {
        var ocp = doc.getDocumentCatalog().getOCProperties();
        return ocp != null && ocp.hasGroup(name);
    }

    public static Result importCuts(String path) throws IOException {
        return importCuts(path, DEFAULT_LAYER, DEFAULT_OFFSET_IN, DEFAULT_OFFSET_IN);
    }

    private boolean inTargetLayer() {
        return layerName == null || (!ocStack.isEmpty() && ocStack.peek());
    }

    private double[] m(double x, double y) {
        // PDFBox page space is bottom-left, Y up; convert to machine inches (top-left, Y down) + offset.
        return new double[]{x * PT + offX, (pageH - y) * PT + offY};
    }

    // --- optional-content tracking via the BDC/BMC/EMC marked-content operators ---
    @Override
    protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,
                                   java.util.List<COSBase> operands) throws IOException {
        String op = operator.getName();
        if ("BDC".equals(op) || "BMC".equals(op)) {
            boolean inside = !ocStack.isEmpty() && ocStack.peek();   // nested markers inherit
            if (layerName != null && "BDC".equals(op) && operands.size() >= 2
                    && COSName.OC.equals(operands.get(0))) {
                String name = resolveOcgName(operands.get(1));
                if (name != null) inside = layerName.equals(name);
            }
            ocStack.push(inside);
        } else if ("EMC".equals(op)) {
            if (!ocStack.isEmpty()) ocStack.pop();
        }
        super.processOperator(operator, operands);
    }

    private String resolveOcgName(COSBase properties) {
        try {
            PDResources res = getResources();
            if (properties instanceof COSName cn && res != null) {
                var p = res.getProperties(cn);
                if (p instanceof PDOptionalContentGroup ocg) return ocg.getName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // --- path construction ---
    @Override public void moveTo(float x, float y) {
        endSubpath();
        current.add(m(x, y));
        curX = startX = x; curY = startY = y;
    }

    @Override public void lineTo(float x, float y) {
        if (current.isEmpty()) current.add(m(curX, curY));
        current.add(m(x, y));
        curX = x; curY = y;
    }

    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (current.isEmpty()) current.add(m(curX, curY));
        double px = curX, py = curY;
        int n = segmentsFor(px, py, x1, y1, x2, y2, x3, y3);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n, mt = 1 - t;
            double bx = mt*mt*mt*px + 3*mt*mt*t*x1 + 3*mt*t*t*x2 + t*t*t*x3;
            double by = mt*mt*mt*py + 3*mt*mt*t*y1 + 3*mt*t*t*y2 + t*t*t*y3;
            current.add(m(bx, by));
        }
        curX = x3; curY = y3;
    }

    /** Segment count so the chord error stays under FLATTEN_TOL_PT (standard control-polygon bound). */
    static int segmentsFor(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
        double ddx = Math.max(Math.abs(x0 - 2 * x1 + x2), Math.abs(x1 - 2 * x2 + x3));
        double ddy = Math.max(Math.abs(y0 - 2 * y1 + y2), Math.abs(y1 - 2 * y2 + y3));
        double dd = Math.hypot(ddx, ddy);
        int n = (int) Math.ceil(Math.sqrt(0.75 * dd / FLATTEN_TOL_PT));
        return Math.max(2, Math.min(64, n));
    }

    @Override public Point2D getCurrentPoint() { return new Point2D.Float((float) curX, (float) curY); }

    @Override public void closePath() {
        if (current.size() > 1) current.add(current.get(0).clone());
        curX = startX; curY = startY;
        endSubpath();
    }

    /** {@code n}: path consumed without painting (clipping only) — not a cut. */
    @Override public void endPath() { discard(); }

    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        endSubpath();
        current.add(m(p0.getX(), p0.getY()));
        current.add(m(p1.getX(), p1.getY()));
        current.add(m(p2.getX(), p2.getY()));
        current.add(m(p3.getX(), p3.getY()));
        current.add(m(p0.getX(), p0.getY()));
        curX = startX = p0.getX(); curY = startY = p0.getY();
        endSubpath();
    }

    @Override public void strokePath() { commit(); }
    @Override public void fillPath(int windingRule) { commit(); }       // treat filled outlines as cut too
    @Override public void fillAndStrokePath(int windingRule) { commit(); }

    private void endSubpath() {
        if (current.size() > 1) pending.add(current);
        current = new ArrayList<>();
    }

    private void commit() {
        endSubpath();
        if (inTargetLayer()) out.addAll(pending);
        discard();
    }

    private void discard() { pending.clear(); current = new ArrayList<>(); }

    // --- live text: cut each glyph's outline (Type 3 glyphs arrive as ordinary paths via the engine) ---
    @Override
    protected void showFontGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
        if (!mode.isFill() && !mode.isStroke()) return;          // invisible / clip-only text (e.g. OCR layers)
        if (!inTargetLayer() || !(font instanceof PDVectorFont vf)) return;
        GeneralPath glyph = vf.getNormalizedPath(code);
        if (glyph == null) return;
        AffineTransform at = trm.createAffineTransform();       // text space -> page space
        at.concatenate(font.getFontMatrix().createAffineTransform());
        List<double[]> sub = null;
        double[] c = new double[6];
        for (PathIterator it = glyph.getPathIterator(at, FLATTEN_TOL_PT); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { addGlyphContour(sub); sub = new ArrayList<>(); sub.add(m(c[0], c[1])); }
                case PathIterator.SEG_LINETO -> { if (sub != null) sub.add(m(c[0], c[1])); }
                case PathIterator.SEG_CLOSE -> {
                    if (sub != null && sub.size() > 1) sub.add(sub.get(0).clone());
                    addGlyphContour(sub); sub = null;
                }
                default -> {}   // flattened iterator yields no curves
            }
        }
        addGlyphContour(sub);
    }

    /** Keep a glyph contour unless it's degenerate (some fonts carry zero-size stray points: a blade plunge for nothing). */
    private void addGlyphContour(List<double[]> sub) {
        if (sub == null || sub.size() < 2) return;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : sub) { x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]); x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]); }
        if (x1 - x0 < MIN_CONTOUR_IN && y1 - y0 < MIN_CONTOUR_IN) return;
        out.add(sub);
    }

    // --- unused abstract methods ---
    @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage i) {}
    @Override public void clip(int windingRule) {}
    @Override public void shadingFill(COSName shadingName) {}
}
