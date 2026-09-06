package io.aerodyne.weather.metar;

/**
 * How much of the sky a cloud layer covers, in the eighths ("oktas") that a report uses.
 *
 * <p>The distinction that matters operationally is whether a layer is <em>broken or worse</em>:
 * that is what constitutes a ceiling, and a ceiling is half of what an approach minimum is
 * expressed in.
 */
public enum CloudAmount {

    /** {@code FEW}: one to two oktas. */
    FEW("FEW", 2, false),
    /** {@code SCT}: three to four oktas. */
    SCATTERED("SCT", 4, false),
    /** {@code BKN}: five to seven oktas — a ceiling. */
    BROKEN("BKN", 7, true),
    /** {@code OVC}: eight oktas — a ceiling. */
    OVERCAST("OVC", 8, true),
    /** {@code VV}: sky obscured, the vertical visibility given instead of a cloud base. */
    VERTICAL_VISIBILITY("VV", 8, true),
    /** {@code SKC}, {@code NSC}, {@code NCD}, {@code CLR}: nothing to report. */
    NONE("NSC", 0, false);

    private final String code;
    private final int maximumOktas;
    private final boolean constitutesCeiling;

    CloudAmount(String code, int maximumOktas, boolean constitutesCeiling) {
        this.code = code;
        this.maximumOktas = maximumOktas;
        this.constitutesCeiling = constitutesCeiling;
    }

    public String code() {
        return code;
    }

    public int maximumOktas() {
        return maximumOktas;
    }

    /** True for a layer that counts as a ceiling: broken, overcast, or an obscured sky. */
    public boolean constitutesCeiling() {
        return constitutesCeiling;
    }

    /** Reads the three-letter amount, or {@code null} when the token is not one. */
    public static CloudAmount fromCode(String code) {
        return switch (code) {
            case "FEW" -> FEW;
            case "SCT" -> SCATTERED;
            case "BKN" -> BROKEN;
            case "OVC" -> OVERCAST;
            case "VV" -> VERTICAL_VISIBILITY;
            case "SKC", "NSC", "NCD", "CLR" -> NONE;
            default -> null;
        };
    }
}
