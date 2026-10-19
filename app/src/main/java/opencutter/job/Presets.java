package opencutter.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Materials and the Less/Default/More pressure model, matching the machine dial and Design Space.
 *
 * Each material has a Default pressure; Design Space's More/Default/Less adjustment shifts it by a
 * fixed step (measured as 8 on Copy Paper: Less 149 / Default 157 / More 165). The dial's labelled
 * positions are the material set; "Custom" lets the user pick one of these from the list.
 */
public final class Presets {
    private Presets() {}

    /** Pressure adjustment (the Design Space More/Default/Less dropdown), ±8 as measured. */
    public enum Level {
        LESS("Less", -8), DEFAULT("Default", 0), MORE("More", +8);
        public final String label; public final int offset;
        Level(String l, int o) { label = l; offset = o; }
        @Override public String toString() { return label; }
    }

    /** The full dial scale (15 stops): one minus at the start, then each material followed by its plus. */
    public static final Map<String, Integer> MATERIALS = new LinkedHashMap<>() {{
        put("Paper −", 145);
        put("Paper", 157);
        put("Paper +", 168);
        put("Vinyl", 180);
        put("Vinyl +", 191);
        put("Iron-On", 203);
        put("Iron-On +", 214);
        put("Light Cardstock", 226);
        put("Light Cardstock +", 238);
        put("Cardstock", 249);
        put("Cardstock +", 261);
        put("Bonded Fabric", 272);
        put("Bonded Fabric +", 284);
        put("Poster Board", 295);
        put("Poster Board +", 307);
    }};

    public static int pressure(String material, Level level) {
        return MATERIALS.getOrDefault(material, 157) + level.offset;
    }

    /** Default pressure for a dial position (the dial IS the material when not on Custom). */
    public static int dialDefaultPressure(int dial) {
        if (dial <= 1) return 157;              // Custom (handled via the material list instead)
        int stop = dial - 1;                    // dial 2 -> stop 1 ... dial 16 -> stop 15
        return (int) Math.round(145 + (307 - 145) * (stop - 1) / 14.0);
    }

    public static int dialPressure(int dial, Level level) {
        return dialDefaultPressure(dial) + level.offset;
    }

    /** Dial stop -> material label (matches the machine's printed wheel). */
    public static String dialLabel(int dial) {
        return switch (dial) {
            case 1 -> "Custom";
            case 3 -> "Paper";
            case 5 -> "Vinyl";
            case 7 -> "Iron-On";
            case 9 -> "Light Cardstock";
            case 11 -> "Cardstock";
            case 13 -> "Bonded Fabric";
            case 15 -> "Poster Board";
            default -> "between stops (" + (dial - 1) + ")";
        };
    }
}
