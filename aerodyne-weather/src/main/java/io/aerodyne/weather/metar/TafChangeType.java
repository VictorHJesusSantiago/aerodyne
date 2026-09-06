package io.aerodyne.weather.metar;

/**
 * How a TAF change group relates to the forecast before it.
 *
 * <p>The distinction drives everything downstream. A {@code FM} or {@code BECMG} group
 * <em>replaces</em> the prevailing forecast from its time onwards; a {@code TEMPO} or
 * {@code PROB} group describes a fluctuation that may or may not occur within its period and
 * never replaces anything. Dispatch has to plan for the prevailing conditions and hold fuel
 * and alternates for the fluctuations, so the two must not be conflated.
 */
public enum TafChangeType {

    /** The body of the TAF: what is forecast from the start of the validity period. */
    INITIAL(true),
    /** {@code FM}: an immediate and complete change from the stated time. */
    FROM(true),
    /** {@code BECMG}: a gradual change completing within the stated period. */
    BECOMING(true),
    /** {@code TEMPO}: fluctuations lasting less than an hour each within the period. */
    TEMPORARY(false),
    /** {@code PROBnn}: a probability of the stated conditions within the period. */
    PROBABILITY(false);

    private final boolean replacesPrevailing;

    TafChangeType(boolean replacesPrevailing) {
        this.replacesPrevailing = replacesPrevailing;
    }

    /** True when the group changes what is prevailing rather than describing a fluctuation. */
    public boolean replacesPrevailing() {
        return replacesPrevailing;
    }
}
