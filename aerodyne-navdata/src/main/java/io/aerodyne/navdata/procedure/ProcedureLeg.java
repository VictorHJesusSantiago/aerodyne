package io.aerodyne.navdata.procedure;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One leg of a departure, arrival or approach procedure.
 *
 * <p>A leg is a {@link LegType} — a path and what terminates it — plus whatever that particular
 * type needs: a fix for the ones that end at one, a navaid and a radial for the ones defined
 * against a station, a course, a distance. Most fields are therefore empty on most legs, and
 * which ones are meaningful is decided by the leg type rather than by whether they happen to be
 * filled in.
 *
 * @param sequenceNumber      position within the transition; records are published in this order
 * @param legType             the path and terminator
 * @param fixIdent            the fix the leg ends at, or {@code null} when it does not end at one
 * @param fixRegion           the ICAO region the fix is published under
 * @param turnDirection       the direction of turn onto the leg
 * @param recommendedNavaid   the navaid defining the path, for the leg types that need one
 * @param navaidRegion        that navaid's ICAO region
 * @param thetaDeg            magnetic bearing from the recommended navaid, degrees
 * @param rhoNm               distance from the recommended navaid, nautical miles
 * @param magneticCourseDeg   the course or heading flown, magnetic
 * @param routeDistanceNm     the leg's length, or the holding time for a holding leg
 * @param altitude            the altitude constraint
 * @param speed               the speed restriction
 * @param descriptionCode     the four-character waypoint description; its fourth character
 *                            marks an end-of-transition and its second a fly-over waypoint
 * @param verticalAngleDeg    the published descent angle, negative downwards
 */
public record ProcedureLeg(int sequenceNumber,
                           LegType legType,
                           String fixIdent,
                           String fixRegion,
                           TurnDirection turnDirection,
                           String recommendedNavaid,
                           String navaidRegion,
                           Double thetaDeg,
                           Double rhoNm,
                           Double magneticCourseDeg,
                           Double routeDistanceNm,
                           AltitudeConstraint altitude,
                           SpeedConstraint speed,
                           String descriptionCode,
                           Double verticalAngleDeg) {

    public ProcedureLeg {
        Objects.requireNonNull(legType, "legType");
        turnDirection = turnDirection == null ? TurnDirection.UNSPECIFIED : turnDirection;
        altitude = altitude == null ? AltitudeConstraint.NONE : altitude;
        speed = speed == null ? SpeedConstraint.NONE : speed;
        descriptionCode = descriptionCode == null ? "" : descriptionCode;
    }

    /**
     * True when the leg both names a fix and is of a type that ends at one. A {@code CA} leg
     * sometimes carries a fix identifier for reference, and treating that as the leg's endpoint
     * would put the aircraft somewhere it was never cleared to.
     */
    public boolean endsAtFix() {
        return legType.hasFixedTerminator() && fixIdent != null && !fixIdent.isBlank();
    }

    /**
     * True when the waypoint must be overflown before turning, rather than cut. The second
     * character of the description code carries this, and it changes the ground track.
     */
    public boolean isFlyOver() {
        return descriptionCode.length() >= 2 && Character.toUpperCase(descriptionCode.charAt(1)) == 'Y';
    }

    /** True when this leg ends the transition it belongs to. */
    public boolean endsTransition() {
        return descriptionCode.length() >= 4 && Character.toUpperCase(descriptionCode.charAt(3)) == 'Y';
    }

    /**
     * True when the leg cannot be resolved to a position in advance — a heading to an altitude,
     * a course to a manual termination. A plan that crosses one of these is showing an estimate,
     * and the OFP has to say so rather than printing a coordinate.
     */
    public boolean isUnresolvable() {
        return !legType.hasFixedTerminator();
    }

    public Optional<String> fix() {
        return Optional.ofNullable(fixIdent);
    }

    public Optional<String> navaid() {
        return Optional.ofNullable(recommendedNavaid);
    }

    public OptionalDouble course() {
        return magneticCourseDeg == null ? OptionalDouble.empty() : OptionalDouble.of(magneticCourseDeg);
    }

    public OptionalDouble distanceNm() {
        return routeDistanceNm == null ? OptionalDouble.empty() : OptionalDouble.of(routeDistanceNm);
    }

    /** How a chart writes the leg: {@code TF KOLIN 5000A 220K}. */
    public String toChartString() {
        StringBuilder rendered = new StringBuilder(legType.code());
        if (fixIdent != null && !fixIdent.isBlank()) {
            rendered.append(' ').append(fixIdent);
        } else if (magneticCourseDeg != null) {
            rendered.append(String.format(java.util.Locale.ROOT, " %03.0f", magneticCourseDeg));
        }
        if (altitude.isConstrained()) {
            rendered.append(' ').append(altitude.toChartString());
        }
        if (speed.isConstrained()) {
            rendered.append(' ').append(speed.speedKt()).append('K');
        }
        if (isFlyOver()) {
            rendered.append(" (flyover)");
        }
        return rendered.toString();
    }

    @Override
    public String toString() {
        return toChartString();
    }
}
