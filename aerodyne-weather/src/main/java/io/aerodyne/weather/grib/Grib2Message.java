package io.aerodyne.weather.grib;

import io.aerodyne.weather.LatLonGrid;

import java.time.Instant;

/**
 * One decoded GRIB2 message: a single field, on a single surface, valid at a single time.
 *
 * <p>A forecast file is a concatenation of these. Assembling them into something a flight plan
 * can use — pairing the U and V components on each level, stacking the levels, ordering the
 * valid times — is {@link UpperAirForecast}'s job, not the decoder's.
 *
 * @param parameter          which field this is
 * @param referenceTime      the forecast's reference time, from section 1
 * @param validAt            reference time plus the forecast lead time
 * @param levelType          code table 4.5 surface type; 100 is an isobaric surface
 * @param levelValue         the surface's value in its own units — pascals for type 100
 * @param grid               the decoded field
 */
public record Grib2Message(Grib2Parameter parameter,
                           Instant referenceTime,
                           Instant validAt,
                           int levelType,
                           double levelValue,
                           LatLonGrid grid) {

    /** Code table 4.5: an isobaric surface, which is what upper-air forecasts are published on. */
    public static final int ISOBARIC_SURFACE = 100;

    public boolean isIsobaric() {
        return levelType == ISOBARIC_SURFACE;
    }

    /** The isobaric surface in hectopascals, the unit the levels are named by. */
    public double pressureHpa() {
        if (!isIsobaric()) {
            throw new IllegalStateException(
                    "level type " + levelType + " is not an isobaric surface");
        }
        return levelValue / 100.0;
    }

    @Override
    public String toString() {
        return parameter + " at "
                + (isIsobaric() ? Math.round(pressureHpa()) + " hPa" : "level type " + levelType)
                + " valid " + validAt;
    }
}
