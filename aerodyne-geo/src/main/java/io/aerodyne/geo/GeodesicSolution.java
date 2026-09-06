package io.aerodyne.geo;

/**
 * The solution of a geodesic between two points on an ellipsoid.
 *
 * @param distanceMetres    length of the geodesic
 * @param initialBearingDeg true course leaving the origin, degrees in [0, 360)
 * @param finalBearingDeg   true course arriving at the destination, degrees in [0, 360)
 * @param iterations        Vincenty iterations consumed; useful when diagnosing near-antipodal pairs
 */
public record GeodesicSolution(double distanceMetres,
                               double initialBearingDeg,
                               double finalBearingDeg,
                               int iterations) {

    public double distanceNm() {
        return Units.metresToNauticalMiles(distanceMetres);
    }
}
