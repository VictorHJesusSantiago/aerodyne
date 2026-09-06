package io.aerodyne.geo;

/**
 * A reference ellipsoid. Aviation navigation data (ARINC 424, ICAO Annex 15) is published on
 * WGS-84; the other constants exist so that legacy state AIP data can be checked against it.
 *
 * @param semiMajorAxisMetres equatorial radius, {@code a}
 * @param inverseFlattening   {@code 1/f}
 */
public record Ellipsoid(String name, double semiMajorAxisMetres, double inverseFlattening) {

    public static final Ellipsoid WGS84 = new Ellipsoid("WGS-84", 6_378_137.0, 298.257223563);
    public static final Ellipsoid GRS80 = new Ellipsoid("GRS-80", 6_378_137.0, 298.257222101);
    public static final Ellipsoid SPHERE = new Ellipsoid("Sphere", 6_371_008.7714, Double.POSITIVE_INFINITY);

    public double flattening() {
        return 1.0 / inverseFlattening;
    }

    /** Semi-minor axis {@code b = a(1 - f)}. */
    public double semiMinorAxisMetres() {
        return semiMajorAxisMetres * (1.0 - flattening());
    }

    /** First eccentricity squared, {@code e^2 = f(2 - f)}. */
    public double eccentricitySquared() {
        double f = flattening();
        return f * (2.0 - f);
    }

    /** Second eccentricity squared, {@code e'^2 = (a^2 - b^2) / b^2}, used by Vincenty. */
    public double secondEccentricitySquared() {
        double a = semiMajorAxisMetres;
        double b = semiMinorAxisMetres();
        return (a * a - b * b) / (b * b);
    }
}
