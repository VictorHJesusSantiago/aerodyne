package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;

import java.util.Objects;

/**
 * One isobaric surface of a forecast: the wind components and temperature on the 250 hPa level,
 * say, all sharing a grid geometry and a valid time.
 *
 * @param pressureHpa   the isobaric surface this level describes
 * @param eastwardKt    the {@code UGRD} field, converted to knots
 * @param northwardKt   the {@code VGRD} field, converted to knots
 * @param temperatureC  the {@code TMP} field, converted to Celsius; {@code null} when the
 *                      forecast carries winds only, in which case the standard atmosphere fills in
 */
public record UpperAirLevel(double pressureHpa,
                            LatLonGrid eastwardKt,
                            LatLonGrid northwardKt,
                            LatLonGrid temperatureC) {

    public UpperAirLevel {
        Objects.requireNonNull(eastwardKt, "eastwardKt");
        Objects.requireNonNull(northwardKt, "northwardKt");
        if (pressureHpa <= 0.0) {
            throw new IllegalArgumentException("pressure must be positive: " + pressureHpa);
        }
        if (eastwardKt.columns() != northwardKt.columns()
                || eastwardKt.rows() != northwardKt.rows()) {
            throw new IllegalArgumentException(
                    "the wind component grids of the " + pressureHpa + " hPa level differ in shape");
        }
    }

    /** The pressure altitude of this surface in the standard atmosphere. */
    public double pressureAltitudeFt() {
        return StandardAtmosphere.pressureAltitudeFt(pressureHpa);
    }

    public WindVector windAt(GeoPoint position) {
        return WindVector.fromComponents(eastwardKt.sample(position), northwardKt.sample(position));
    }

    /** Temperature at a position, falling back to the standard atmosphere for a winds-only file. */
    public double temperatureAt(GeoPoint position) {
        return temperatureC != null
                ? temperatureC.sample(position)
                : io.aerodyne.geo.Units.isaTemperatureC(pressureAltitudeFt());
    }

    public AtmosphericConditions conditionsAt(GeoPoint position) {
        return new AtmosphericConditions(windAt(position), temperatureAt(position));
    }

    public boolean covers(GeoPoint position) {
        return eastwardKt.covers(position);
    }
}
