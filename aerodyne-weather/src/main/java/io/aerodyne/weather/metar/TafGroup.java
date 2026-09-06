package io.aerodyne.weather.metar;

import java.time.Instant;

/**
 * One group of a TAF: a set of conditions, the period they apply to, and how they relate to
 * what came before.
 *
 * @param changeType   whether this replaces the prevailing forecast or describes a fluctuation
 * @param probability  the percentage of a {@code PROBnn} group; {@code null} for the others
 * @param validFrom    when the group begins to apply
 * @param validTo      when it stops; for a {@code FM} group this is the start of the next one,
 *                     or the end of the TAF's validity when it is the last
 * @param conditions   the forecast conditions
 */
public record TafGroup(TafChangeType changeType,
                       Integer probability,
                       Instant validFrom,
                       Instant validTo,
                       SurfaceConditions conditions) {

    /** True when the group applies at an instant. */
    public boolean appliesAt(Instant when) {
        return !when.isBefore(validFrom) && when.isBefore(validTo);
    }

    /** A copy of this group ending at a different time, used to close an open {@code FM}. */
    public TafGroup endingAt(Instant newValidTo) {
        return new TafGroup(changeType, probability, validFrom, newValidTo, conditions);
    }

    @Override
    public String toString() {
        String label = changeType == TafChangeType.PROBABILITY
                ? "PROB" + probability
                : changeType.name();
        return label + " " + validFrom + "/" + validTo;
    }
}
