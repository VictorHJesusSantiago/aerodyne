package io.aerodyne.geo;

/**
 * A geographic position on the WGS-84 datum, in decimal degrees.
 *
 * <p>Latitude is positive north, longitude positive east. Longitude is normalised into
 * [-180, +180) on construction so that positions built from different sources (ARINC 424
 * records use 0..360 in a few field types) compare and interpolate consistently.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("latitude/longitude must not be NaN");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        longitude = normaliseLongitude(longitude);
    }

    public static GeoPoint of(double latitude, double longitude) {
        return new GeoPoint(latitude, longitude);
    }

    /** Wraps any longitude into [-180, +180). */
    public static double normaliseLongitude(double longitude) {
        if (longitude >= -180.0 && longitude < 180.0) {
            return longitude;
        }
        double wrapped = (longitude + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }

    public double latitudeRadians() {
        return Math.toRadians(latitude);
    }

    public double longitudeRadians() {
        return Math.toRadians(longitude);
    }

    /**
     * True when this point lies within {@code toleranceNm} of {@code other}. Used to collapse
     * duplicate navaid/waypoint records that the same cycle publishes under several idents.
     */
    public boolean isNear(GeoPoint other, double toleranceNm) {
        return Geodesy.distanceNm(this, other) <= toleranceNm;
    }

    /** {@code N5130.0 W00007.5}-style rendering, the form an OFP route listing uses. */
    @Override
    public String toString() {
        return CoordinateFormat.toArincDegreesMinutes(this);
    }
}
