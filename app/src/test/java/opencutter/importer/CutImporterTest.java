package opencutter.importer;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CutImporterTest {

    private static final File SAMPLE = new File("../samples/sample-cut.pdf");

    @Test void sampleFileHasFiveShapesOnLetterSheet() throws IOException {
        assumeSample();
        CutImporter.Result r = CutImporter.importCuts(SAMPLE.getPath());
        assertEquals(5, r.polylines.size());
        assertEquals(8.5, r.sheetWin, 1e-6);
        assertEquals(11.0, r.sheetHin, 1e-6);
        assertEquals(0.25, r.offX, 0);
        // everything lands on the sheet (plus offset)
        assertTrue(r.bbox[0] >= 0.25 && r.bbox[1] >= 0.25);
        assertTrue(r.bbox[2] <= 8.5 + 0.25 && r.bbox[3] <= 11 + 0.25);
        for (List<double[]> pl : r.polylines) assertTrue(pl.size() >= 4);
    }

    @Test void topLeftOriginAndOffset() throws IOException {
        assumeSample();
        // Import with no offset: y must be measured from the top of the page.
        CutImporter.Result r = CutImporter.importCuts(SAMPLE.getPath(), "Cut Layer", 0, 0);
        assertTrue(r.bbox[1] > 0 && r.bbox[3] < 11);
        CutImporter.Result o = CutImporter.importCuts(SAMPLE.getPath(), "Cut Layer", 0.25, 0.25);
        assertEquals(r.bbox[0] + 0.25, o.bbox[0], 1e-9);
        assertEquals(r.bbox[1] + 0.25, o.bbox[1], 1e-9);
    }

    @Test void compoundPathKeepsEverySubpath(@TempDir Path tmp) throws IOException {
        File pdf = tmp.resolve("compound.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDOptionalContentGroup cut = new PDOptionalContentGroup("Cut Layer");
            PDOptionalContentGroup print = new PDOptionalContentGroup("Print Layer");
            PDOptionalContentProperties ocp = new PDOptionalContentProperties();
            ocp.addGroup(cut); ocp.addGroup(print);
            doc.getDocumentCatalog().setOCProperties(ocp);
            page.setResources(new PDResources());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // artboard clip (outside any layer): re W n -> must NOT be cut
                cs.addRect(0, 0, 612, 792); cs.clip();

                cs.beginMarkedContent(COSName.OC, cut);
                // one compound path: outer square + inner square, single stroke
                cs.moveTo(100, 100); cs.lineTo(300, 100); cs.lineTo(300, 300); cs.lineTo(100, 300); cs.closePath();
                cs.moveTo(150, 150); cs.lineTo(250, 150); cs.lineTo(250, 250); cs.lineTo(150, 250); cs.closePath();
                cs.stroke();
                // a clip rect inside the cut layer: also not a cut
                cs.addRect(50, 50, 500, 700); cs.clip();
                // a rectangle via 're' that IS stroked
                cs.addRect(400, 400, 50, 50); cs.stroke();
                // a circle-ish curve, filled
                cs.moveTo(500, 100);
                cs.curveTo(500, 155, 455, 200, 400, 200);
                cs.curveTo(345, 200, 300, 155, 300, 100);
                cs.closePath(); cs.fill();
                cs.endMarkedContent();

                cs.beginMarkedContent(COSName.OC, print);
                cs.addRect(10, 10, 100, 100); cs.stroke();   // print layer: ignored
                cs.endMarkedContent();
            }
            doc.save(pdf);
        }
        CutImporter.Result r = CutImporter.importCuts(pdf.getPath(), "Cut Layer", 0, 0);
        assertEquals(4, r.polylines.size(), "outer, inner, stroked rect, curve");
        List<double[]> outer = r.polylines.get(0), inner = r.polylines.get(1);
        assertEquals(5, outer.size());
        assertEquals(5, inner.size());
        // outer square: x 100..300 pt -> 1.389..4.167 in; top-left origin so y = (792-300)/72 .. (792-100)/72
        assertEquals(100 / 72.0, outer.get(0)[0], 1e-9);
        assertEquals((792 - 100) / 72.0, outer.get(0)[1], 1e-9);
        assertEquals(150 / 72.0, inner.get(0)[0], 1e-9);
        // curve got flattened finely (two big arcs => well over the old fixed 16 points)
        assertTrue(r.polylines.get(3).size() > 20);

        // all layers: the print-layer rect is included too
        assertEquals(5, CutImporter.importCuts(pdf.getPath(), null, 0, 0).polylines.size());
        // wrong layer name: nothing
        assertThrows(IOException.class, () -> CutImporter.importCuts(pdf.getPath(), "Nope", 0, 0));
    }

    @Test void flatteningScalesWithCurveSize() {
        int tiny = CutImporter.segmentsFor(0, 0, 1, 1, 2, 1, 3, 0);
        int big = CutImporter.segmentsFor(0, 0, 100, 100, 200, 100, 300, 0);
        assertTrue(tiny >= 2 && tiny < big && big <= 64);
    }

    private static void assumeSample() {
        org.junit.jupiter.api.Assumptions.assumeTrue(SAMPLE.exists(), "sample PDF present");
    }
}
