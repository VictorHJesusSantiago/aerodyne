package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.Objects;

/**
 * A single runway direction — the ARINC 424 {@code PG} record, which describes one landing
 * direction rather than the whole strip, so a two-way runway appears twice.
 *
 * @param airportIcao      the airport this runway belongs to
 * @param designator       the ARINC designator, e.g. {@code RW27L}
 * @param threshold        the landing threshold position
 * @param magneticBearingDeg published runway bearing, magnetic
 * @param lengthFt         declared length
 * @param widthFt          declared width
 * @param thresholdElevationFt threshold elevation
 * @param displacedThresholdFt displaced threshold distance, zero when none
 * @param gradientPercent  runway gradient, positive uphill in the landing direction
 * @param ilsIdent         the identifier of the associated localiser, {@code null} when none
 */
public record Runway(String airportIcao,
                     String designator,
                     GeoPoint threshold,
                     double magneticBearingDeg,
                     int lengthFt,
                     int widthFt,
                     int thresholdElevationFt,
                     int displacedThresholdFt,
                     double gradientPercent,
                     String ilsIdent) implements Fix {

    public Runway {
        Objects.requireNonNull(airportIcao, "airportIcao");
        Objects.requireNonNull(designator, "designator");
        Objects.requireNonNull(threshold, "threshold");
        if (lengthFt < 0) {
            throw new IllegalArgumentException("negative runway length for " + designator);
        }
    }

    @Override
    public String ident() {
        return designator;
    }

    @Override
    public GeoPoint position() {
        return threshold;
    }

    @Override
    public String icaoRegion() {
        return airportIcao.length() >= 2 ? airportIcao.substring(0, 2) : airportIcao;
    }

    @Override
    public FixKind kind() {
        return FixKind.RUNWAY;
    }

    /** The bare number and side, {@code 27L}, with the ARINC {@code RW} prefix stripped. */
    public String shortDesignator() {
        return designator.startsWith("RW") ? designator.substring(2) : designator;
    }

    /** Length available beyond a displaced threshold. */
    public int landingDistanceAvailableFt() {
        return Math.max(0, lengthFt - displacedThresholdFt);
    }

    public boolean hasPrecisionApproach() {
        return ilsIdent != null && !ilsIdent.isBlank();
    }
}
