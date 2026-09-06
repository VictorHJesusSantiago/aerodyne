package io.aerodyne.navdata;

import java.util.Objects;

/**
 * A single leg of an airway, between two consecutive published points.
 *
 * <p>The rules on the leg come from the record at its start — see {@link Airway#segments()}.
 */
public record AirwaySegment(String airwayIdent, AirwayPoint from, AirwayPoint to) {

    public AirwaySegment {
        Objects.requireNonNull(airwayIdent, "airwayIdent");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public DirectionRestriction direction() {
        return from.direction();
    }

    public AirwayLevel level() {
        return from.level();
    }

    public Integer minimumAltitudeFt() {
        return from.minimumAltitudeFt();
    }

    public Integer maximumAltitudeFt() {
        return from.maximumAltitudeFt();
    }

    /** True when the leg may be flown at this cruise altitude in the published direction. */
    public boolean usableAt(double altitudeFt) {
        return from.permitsAltitude(altitudeFt);
    }

    /** {@code UN862 KOLIN -> BEDEK}, the form used in diagnostics and route explanations. */
    @Override
    public String toString() {
        return airwayIdent + " " + from.fixIdent() + " -> " + to.fixIdent();
    }
}
