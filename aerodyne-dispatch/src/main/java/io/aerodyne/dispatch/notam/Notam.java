package io.aerodyne.dispatch.notam;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A decoded NOTAM.
 *
 * <p>Dispatch asks three questions of one: does it apply where I am going, does it apply when I
 * am there, and does it stop me. {@link #appliesTo}, {@link #isActiveAt} and
 * {@link #closesAerodrome()} are those three.
 *
 * @param identifier     the series, number and year, e.g. {@code A1234/24}
 * @param type           new, replacement or cancellation
 * @param replacesOrCancels the identifier this one supersedes, for a replacement or cancellation
 * @param fir            the flight information region from the Q line
 * @param qCode          what the NOTAM is about
 * @param trafficType    {@code I}, {@code V} or {@code IV}: which traffic it concerns
 * @param purpose        the purpose letters from the Q line
 * @param scope          {@code A} aerodrome, {@code E} enroute, {@code W} warning
 * @param lowerLimitFt   the lower limit of the affected volume, in hundreds of feet as published
 * @param upperLimitFt   the upper limit
 * @param centre         the centre of the affected area, when the Q line gives one
 * @param radiusNm       the radius of that area
 * @param locations      the aerodromes or FIRs from the A line
 * @param validFrom      when it starts
 * @param validTo        when it ends; empty for a permanent NOTAM
 * @param estimatedEnd   true when the end time was published as an estimate
 * @param schedule       the D line, if any, kept verbatim
 * @param text           the E line: what it actually says
 * @param raw            the NOTAM as received
 */
public record Notam(String identifier,
                    NotamType type,
                    String replacesOrCancels,
                    String fir,
                    QCode qCode,
                    String trafficType,
                    String purpose,
                    String scope,
                    Integer lowerLimitFt,
                    Integer upperLimitFt,
                    GeoPoint centre,
                    Double radiusNm,
                    List<String> locations,
                    Instant validFrom,
                    Instant validTo,
                    boolean estimatedEnd,
                    String schedule,
                    String text,
                    String raw) {

    /** What kind of NOTAM this is. */
    public enum NotamType {
        /** {@code NOTAMN}: new information. */
        NEW,
        /** {@code NOTAMR}: replaces an earlier NOTAM, which stops applying. */
        REPLACEMENT,
        /** {@code NOTAMC}: cancels an earlier NOTAM. */
        CANCELLATION
    }

    public Notam {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    /** True when the NOTAM names an aerodrome, either on its A line or its Q line FIR. */
    public boolean appliesTo(String icaoCode) {
        return locations.stream().anyMatch(location -> location.equalsIgnoreCase(icaoCode));
    }

    /** True when the NOTAM's affected area reaches a position. */
    public boolean appliesAt(GeoPoint position) {
        if (centre == null || radiusNm == null) {
            return false;
        }
        return Geodesy.greatCircleDistanceNm(centre, position) <= radiusNm;
    }

    /** True when the NOTAM is in force at an instant. */
    public boolean isActiveAt(Instant when) {
        if (type == NotamType.CANCELLATION) {
            return false;
        }
        if (validFrom != null && when.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || when.isBefore(validTo);
    }

    /** True when the NOTAM says the aerodrome itself is unavailable. */
    public boolean closesAerodrome() {
        return qCode.concernsAerodrome() && qCode.isUnavailable();
    }

    /** True when it says a runway is unavailable. */
    public boolean closesRunway() {
        return qCode.concernsRunway() && qCode.isUnavailable();
    }

    /** True when it takes an approach aid out of service. */
    public boolean withdrawsApproachAid() {
        return qCode.concernsNavaid() && qCode.isUnavailable();
    }

    /**
     * True when the NOTAM is one that could stop a flight using an aerodrome. Anything else —
     * a crane, a changed frequency, a new taxiway name — is briefing material rather than a
     * dispatch constraint.
     */
    public boolean isOperationallySignificant() {
        return closesAerodrome() || closesRunway() || withdrawsApproachAid();
    }

    /** A permanent NOTAM has no end date; it stands until it is replaced or cancelled. */
    public boolean isPermanent() {
        return validTo == null;
    }

    public OptionalInt lowerLimit() {
        return lowerLimitFt == null ? OptionalInt.empty() : OptionalInt.of(lowerLimitFt);
    }

    public OptionalInt upperLimit() {
        return upperLimitFt == null ? OptionalInt.empty() : OptionalInt.of(upperLimitFt);
    }

    public Optional<GeoPoint> affectedCentre() {
        return Optional.ofNullable(centre);
    }

    /** True when the affected vertical band reaches a flight level. */
    public boolean affectsAltitude(double altitudeFt) {
        if (lowerLimitFt != null && altitudeFt < lowerLimitFt) {
            return false;
        }
        return upperLimitFt == null || altitudeFt <= upperLimitFt;
    }

    /** One line for a briefing package. */
    public String summary() {
        return identifier + " " + qCode + " " + String.join("/", locations)
                + " — " + (text == null ? qCode.describe() : text);
    }

    @Override
    public String toString() {
        return raw == null ? summary() : raw;
    }
}
