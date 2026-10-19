package opencutter.ui;

import opencutter.importer.CutImporter;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Draws the sheet outline and the cut paths to scale, so placement is visible before cutting. */
final class PreviewPanel extends JPanel {
    private CutImporter.Result job;

    PreviewPanel() { setBackground(Color.WHITE); }

    void setJob(CutImporter.Result r) { this.job = r; repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        if (job == null) {
            g0.setColor(Color.GRAY);
            g0.drawString("Load a file to preview", 16, 24);
            return;
        }
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int pad = 16;
        int caption = 22;   // reserve space below the sheet for the caption line
        double sheetW = job.sheetWin, sheetH = job.sheetHin;
        double sc = Math.min((getWidth() - 2.0 * pad) / sheetW,
                             (getHeight() - 2.0 * pad - caption) / sheetH);
        int sheetPx = (int) (sheetW * sc), sheetPy = (int) (sheetH * sc);
        int ox = (getWidth() - sheetPx) / 2, oy = pad;   // centre horizontally

        // sheet
        g.setColor(new Color(245, 245, 245));
        g.fillRect(ox, oy, sheetPx, sheetPy);
        g.setColor(Color.LIGHT_GRAY);
        g.drawRect(ox, oy, sheetPx, sheetPy);

        // cut paths (coordinates are machine inches = sheet inches + mat offset; subtract to draw on the sheet)
        double offX = job.offX, offY = job.offY;
        g.setColor(new Color(0, 110, 200));
        g.setStroke(new BasicStroke(1.2f));
        for (List<double[]> pl : job.polylines) {
            for (int i = 1; i < pl.size(); i++) {
                double[] a = pl.get(i - 1), b = pl.get(i);
                int ax = ox + (int) ((a[0] - offX) * sc), ay = oy + (int) ((a[1] - offY) * sc);
                int bx = ox + (int) ((b[0] - offX) * sc), by = oy + (int) ((b[1] - offY) * sc);
                g.drawLine(ax, ay, bx, by);
            }
        }
        g.setColor(Color.DARK_GRAY);
        String cap = job.polylines.size() + " cuts  •  " + fmt(sheetW) + " × " + fmt(sheetH) + " in";
        int cw = g.getFontMetrics().stringWidth(cap);
        g.drawString(cap, (getWidth() - cw) / 2, oy + sheetPy + 16);
    }

    private static String fmt(double v) { return String.valueOf(Math.round(v * 100) / 100.0); }
}
