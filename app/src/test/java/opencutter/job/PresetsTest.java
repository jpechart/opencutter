package opencutter.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresetsTest {
    @Test void copyPaperMatchesMeasuredValues() {
        assertEquals(149, Presets.pressure("Paper", Presets.Level.LESS));
        assertEquals(157, Presets.pressure("Paper", Presets.Level.DEFAULT));
        assertEquals(165, Presets.pressure("Paper", Presets.Level.MORE));
    }

    @Test void dialStopsAgreeWithMaterialTable() {
        // dial 2..16 = stops 1..15 = MATERIALS in order
        int dial = 2;
        for (int p : Presets.MATERIALS.values()) {
            assertEquals(p, Presets.dialDefaultPressure(dial), "dial " + dial);
            dial++;
        }
    }

    @Test void dialLabelsLandOnMaterials() {
        assertEquals("Custom", Presets.dialLabel(1));
        assertEquals("Paper", Presets.dialLabel(3));
        assertEquals("Poster Board", Presets.dialLabel(15));
    }
}
