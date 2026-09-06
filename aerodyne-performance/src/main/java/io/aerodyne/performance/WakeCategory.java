package io.aerodyne.performance;

/**
 * The ICAO wake turbulence category, filed in item 9 of the flight plan and used to work out
 * separation minima.
 */
public enum WakeCategory {

    /** {@code L}: 7000 kg or less. */
    LIGHT('L'),
    /** {@code M}: more than 7000 kg and less than 136 000 kg. */
    MEDIUM('M'),
    /** {@code H}: 136 000 kg or more. */
    HEAVY('H'),
    /** {@code J}: the A380, which has its own separation minima. */
    SUPER('J');

    private final char code;

    WakeCategory(char code) {
        this.code = code;
    }

    /** The single letter filed in the flight plan. */
    public char code() {
        return code;
    }

    /**
     * The category a maximum takeoff weight falls into. The A380's {@code SUPER} category is
     * assigned by type rather than by weight, so it is never returned here.
     */
    public static WakeCategory forMaximumTakeoffWeightKg(double maxTakeoffKg) {
        if (maxTakeoffKg <= 7_000) {
            return LIGHT;
        }
        return maxTakeoffKg >= 136_000 ? HEAVY : MEDIUM;
    }
}
