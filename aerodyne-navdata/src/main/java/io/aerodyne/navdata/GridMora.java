package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Grid minimum off-route altitudes: the lowest altitude that clears the terrain and obstacles in
 * each one-degree square of the earth.
 *
 * <p>This is what a flight plan is checked against when it leaves an airway. An airway carries
 * its own minimum enroute altitude, published leg by leg, and inside that structure the question
 * does not arise. A direct routing has no such figure, and neither does a diversion, which is
 * where it matters most: an engine failure over terrain turns "how low can we go" into the only
 * question, and grid MORA is the answer that was surveyed rather than guessed.
 *
 * <p>ARINC 424 publishes it as one record per one-degree band of latitude, carrying thirty
 * values eastward from a starting longitude — so a full world file is 180 records of 30 squares.
 * A square whose value is unknown is unknown, not zero; {@link #UNKNOWN} keeps the two apart,
 * because a plan that read an unsurveyed square as sea level would descend into it.
 *
 * @param southLatitude the southern edge of the band, in whole degrees
 * @param westLongitude the western edge of the first square, in whole degrees
 * @param altitudesFt   one value per one-degree square, eastward; {@link #UNKNOWN} where the
 *                      file gives none
 */
public record GridMora(int southLatitude, int westLongitude, int[] altitudesFt) {

    /** The value for a square the file does not give an altitude for. */
    public static final int UNKNOWN = -1;

    /** How many one-degree squares one ARINC 424 grid MORA record carries. */
    public static final int SQUARES_PER_RECORD = 30;

    public GridMora {
        Objects.requireNonNull(altitudesFt, "altitudesFt");
        if (southLatitude < -90 || southLatitude > 89) {
            throw new IllegalArgumentException("latitude band out of range: " + southLatitude);
        }
        if (westLongitude < -180 || westLongitude > 179) {
            throw new IllegalArgumentException("longitude out of range: " + westLongitude);
        }
        altitudesFt = altitudesFt.clone();
    }

    /**
     * The altitude for the square containing a position, or empty when this band does not cover
     * it or the square was never surveyed.
     */
    public OptionalInt altitudeAt(GeoPoint position) {
        int latitude = (int) Math.floor(position.latitude());
        if (latitude != southLatitude) {
            return OptionalInt.empty();
        }
        int index = (int) Math.floor(position.longitude()) - westLongitude;
        if (index < 0 || index >= altitudesFt.length) {
            return OptionalInt.empty();
        }
        int value = altitudesFt[index];
        return value == UNKNOWN ? OptionalInt.empty() : OptionalInt.of(value);
    }

    /** True when this record covers the square a position falls in, surveyed or not. */
    public boolean covers(GeoPoint position) {
        int latitude = (int) Math.floor(position.latitude());
        int index = (int) Math.floor(position.longitude()) - westLongitude;
        return latitude == southLatitude && index >= 0 && index < altitudesFt.length;
    }

    /** The eastern edge of the last square this record carries. */
    public int eastLongitude() {
        return westLongitude + altitudesFt.length;
    }

    /** How many of the squares carry a surveyed altitude. */
    public int surveyedCount() {
        return (int) Arrays.stream(altitudesFt).filter(value -> value != UNKNOWN).count();
    }

    @Override
    public int[] altitudesFt() {
        return altitudesFt.clone();
    }

    // A record's generated equals compares array identity, which for a value type is wrong.

    @Override
    public boolean equals(Object other) {
        return other instanceof GridMora mora
                && southLatitude == mora.southLatitude
                && westLongitude == mora.westLongitude
                && Arrays.equals(altitudesFt, mora.altitudesFt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(southLatitude, westLongitude, Arrays.hashCode(altitudesFt));
    }

    @Override
    public String toString() {
        return String.format("MORA %+03d %+04d..%+04d, %d of %d surveyed",
                southLatitude, westLongitude, eastLongitude(), surveyedCount(), altitudesFt.length);
    }
}
