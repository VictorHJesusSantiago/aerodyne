package io.aerodyne.weather.metar;

/**
 * One reported cloud layer.
 *
 * @param amount        how much sky it covers
 * @param baseFtAgl     base height above aerodrome level; {@code null} when the layer is
 *                      reported without a height, which happens when the sky is obscured and
 *                      even the vertical visibility is unmeasured
 * @param convective    {@code CB} or {@code TCU} was appended, so the layer carries a hazard
 *                      beyond its height
 */
public record CloudLayer(CloudAmount amount, Integer baseFtAgl, String convective) {

    /** {@code NSC} and its relatives: no layer at all. */
    public static final CloudLayer NONE = new CloudLayer(CloudAmount.NONE, null, null);

    /** True when this layer is a ceiling — broken or worse, at a known height. */
    public boolean isCeiling() {
        return amount.constitutesCeiling() && baseFtAgl != null;
    }

    /** True when the layer was reported as cumulonimbus or towering cumulus. */
    public boolean isConvective() {
        return convective != null && !convective.isBlank();
    }

    @Override
    public String toString() {
        if (amount == CloudAmount.NONE) {
            return "NSC";
        }
        String height = baseFtAgl == null ? "///" : String.format("%03d", baseFtAgl / 100);
        return amount.code() + height + (convective == null ? "" : convective);
    }
}
