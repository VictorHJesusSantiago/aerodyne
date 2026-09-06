package io.aerodyne.geo;

/**
 * Navigational geodesy.
 *
 * <p>Two families of computation live here and they answer different questions:
 *
 * <ul>
 *   <li><b>Ellipsoidal (Vincenty)</b> — {@link #inverse} and {@link #direct}. Accurate to
 *       well under a metre on WGS-84. Used wherever a published distance must be reproduced:
 *       ETOPS entry points, equal-time points, leg distances printed on the OFP.</li>
 *   <li><b>Spherical</b> — {@link #greatCircleDistanceNm}, {@link #intermediatePoint},
 *       {@link #crossTrackDistanceNm}. Cheap and closed-form, so they are what the route
 *       search calls in its inner loop and what samples a leg for wind interpolation. The
 *       error against Vincenty is roughly 0.3 %, which the search heuristic tolerates.</li>
 * </ul>
 */
public final class Geodesy {

    /** Vincenty's inverse solution converges in a handful of steps except near antipodal pairs. */
    private static final int MAX_ITERATIONS = 200;
    private static final double CONVERGENCE_THRESHOLD = 1e-12;

    private Geodesy() {
    }

    // ---------------------------------------------------------------- ellipsoidal

    /**
     * Vincenty's inverse solution: distance and bearings between two points on WGS-84.
     *
     * @throws ArithmeticException when the pair is so nearly antipodal that the iteration does
     *                             not converge; the caller should fall back to the spherical form
     */
    public static GeodesicSolution inverse(GeoPoint from, GeoPoint to) {
        return inverse(from, to, Ellipsoid.WGS84);
    }

    public static GeodesicSolution inverse(GeoPoint from, GeoPoint to, Ellipsoid ellipsoid) {
        double a = ellipsoid.semiMajorAxisMetres();
        double b = ellipsoid.semiMinorAxisMetres();
        double f = ellipsoid.flattening();

        double L = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        // Reduced latitudes: the latitude of the point projected onto the auxiliary sphere.
        double U1 = Math.atan((1.0 - f) * Math.tan(from.latitudeRadians()));
        double U2 = Math.atan((1.0 - f) * Math.tan(to.latitudeRadians()));
        double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);

        double lambda = L;
        double sinLambda, cosLambda;
        double sinSigma = 0, cosSigma = 0, sigma = 0;
        double sinAlpha, cosSqAlpha = 0, cos2SigmaM = 0;
        int iteration = 0;

        do {
            sinLambda = Math.sin(lambda);
            cosLambda = Math.cos(lambda);
            double t1 = cosU2 * sinLambda;
            double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            sinSigma = Math.sqrt(t1 * t1 + t2 * t2);
            if (sinSigma == 0.0) {
                // Coincident points; every bearing is as good as any other, report north.
                return new GeodesicSolution(0.0, 0.0, 0.0, iteration);
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            // Zero on an equatorial line, where cos(2*sigma_m) is undefined; zero is the
            // value that makes the series below collapse to the equatorial case.
            cos2SigmaM = cosSqAlpha == 0.0 ? 0.0 : cosSigma - 2.0 * sinU1 * sinU2 / cosSqAlpha;

            double C = f / 16.0 * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha));
            double lambdaPrev = lambda;
            lambda = L + (1.0 - C) * f * sinAlpha
                    * (sigma + C * sinSigma
                    * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)));

            if (Math.abs(lambda - lambdaPrev) < CONVERGENCE_THRESHOLD) {
                break;
            }
        } while (++iteration < MAX_ITERATIONS);

        if (iteration >= MAX_ITERATIONS) {
            throw new ArithmeticException(
                    "Vincenty inverse failed to converge (near-antipodal points " + from + " -> " + to + ")");
        }

        double uSq = cosSqAlpha * ellipsoid.secondEccentricitySquared();
        double A = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
        double B = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));
        double deltaSigma = B * sinSigma * (cos2SigmaM + B / 4.0
                * (cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                - B / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma)
                * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)));

        double distance = b * A * (sigma - deltaSigma);

        sinLambda = Math.sin(lambda);
        cosLambda = Math.cos(lambda);
        double alpha1 = Math.atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda);
        double alpha2 = Math.atan2(cosU1 * sinLambda, -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda);

        return new GeodesicSolution(distance, normaliseBearing(Math.toDegrees(alpha1)),
                normaliseBearing(Math.toDegrees(alpha2)), iteration);
    }

    /**
     * Vincenty's direct solution: the point reached by leaving {@code from} on
     * {@code initialBearingDeg} and running {@code distanceMetres} along the geodesic.
     */
    public static GeoPoint direct(GeoPoint from, double initialBearingDeg, double distanceMetres) {
        return direct(from, initialBearingDeg, distanceMetres, Ellipsoid.WGS84);
    }

    public static GeoPoint direct(GeoPoint from, double initialBearingDeg, double distanceMetres,
                                  Ellipsoid ellipsoid) {
        if (distanceMetres == 0.0) {
            return from;
        }
        double a = ellipsoid.semiMajorAxisMetres();
        double b = ellipsoid.semiMinorAxisMetres();
        double f = ellipsoid.flattening();

        double alpha1 = Math.toRadians(initialBearingDeg);
        double sinAlpha1 = Math.sin(alpha1), cosAlpha1 = Math.cos(alpha1);

        double tanU1 = (1.0 - f) * Math.tan(from.latitudeRadians());
        double cosU1 = 1.0 / Math.sqrt(1.0 + tanU1 * tanU1);
        double sinU1 = tanU1 * cosU1;

        double sigma1 = Math.atan2(tanU1, cosAlpha1);
        double sinAlpha = cosU1 * sinAlpha1;
        double cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
        double uSq = cosSqAlpha * ellipsoid.secondEccentricitySquared();
        double A = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
        double B = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));

        double sigma = distanceMetres / (b * A);
        double sinSigma, cosSigma, cos2SigmaM, deltaSigma;
        int iteration = 0;
        double sigmaPrev;
        do {
            cos2SigmaM = Math.cos(2.0 * sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            deltaSigma = B * sinSigma * (cos2SigmaM + B / 4.0
                    * (cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                    - B / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma)
                    * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)));
            sigmaPrev = sigma;
            sigma = distanceMetres / (b * A) + deltaSigma;
        } while (Math.abs(sigma - sigmaPrev) > CONVERGENCE_THRESHOLD && ++iteration < MAX_ITERATIONS);

        double t = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1;
        double lat2 = Math.atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                (1.0 - f) * Math.sqrt(sinAlpha * sinAlpha + t * t));
        double lambda = Math.atan2(sinSigma * sinAlpha1,
                cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1);
        double C = f / 16.0 * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha));
        double L = lambda - (1.0 - C) * f * sinAlpha
                * (sigma + C * sinSigma
                * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)));

        return new GeoPoint(Math.toDegrees(lat2), from.longitude() + Math.toDegrees(L));
    }

    /** Ellipsoidal distance in nautical miles. */
    public static double distanceNm(GeoPoint from, GeoPoint to) {
        try {
            return inverse(from, to).distanceNm();
        } catch (ArithmeticException nearlyAntipodal) {
            return greatCircleDistanceNm(from, to);
        }
    }

    /** Ellipsoidal initial true course in degrees. */
    public static double initialBearingDeg(GeoPoint from, GeoPoint to) {
        try {
            return inverse(from, to).initialBearingDeg();
        } catch (ArithmeticException nearlyAntipodal) {
            return greatCircleInitialBearingDeg(from, to);
        }
    }

    // ------------------------------------------------------------------ spherical

    /** Great-circle distance on the mean-radius sphere, via the haversine formula. */
    public static double greatCircleDistanceNm(GeoPoint from, GeoPoint to) {
        double phi1 = from.latitudeRadians(), phi2 = to.latitudeRadians();
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        double sinHalfDPhi = Math.sin(dPhi / 2.0);
        double sinHalfDLambda = Math.sin(dLambda / 2.0);
        double h = sinHalfDPhi * sinHalfDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDLambda * sinHalfDLambda;
        double angular = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
        return Units.metresToNauticalMiles(angular * Ellipsoid.SPHERE.semiMajorAxisMetres());
    }

    /** Great-circle initial true course, degrees in [0, 360). */
    public static double greatCircleInitialBearingDeg(GeoPoint from, GeoPoint to) {
        double phi1 = from.latitudeRadians(), phi2 = to.latitudeRadians();
        double dLambda = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return normaliseBearing(Math.toDegrees(Math.atan2(y, x)));
    }

    /**
     * The point a fraction {@code f} of the way along the great circle from {@code from} to
     * {@code to}. {@code f == 0} returns the origin and {@code f == 1} the destination.
     *
     * <p>This is the sampler the wind integration and the ETP search both drive.
     */
    public static GeoPoint intermediatePoint(GeoPoint from, GeoPoint to, double f) {
        if (f <= 0.0) {
            return from;
        }
        if (f >= 1.0) {
            return to;
        }
        double phi1 = from.latitudeRadians(), lambda1 = from.longitudeRadians();
        double phi2 = to.latitudeRadians(), lambda2 = to.longitudeRadians();
        double delta = angularDistanceRadians(from, to);
        if (delta == 0.0) {
            return from;
        }
        double sinDelta = Math.sin(delta);
        double A = Math.sin((1.0 - f) * delta) / sinDelta;
        double B = Math.sin(f * delta) / sinDelta;

        double x = A * Math.cos(phi1) * Math.cos(lambda1) + B * Math.cos(phi2) * Math.cos(lambda2);
        double y = A * Math.cos(phi1) * Math.sin(lambda1) + B * Math.cos(phi2) * Math.sin(lambda2);
        double z = A * Math.sin(phi1) + B * Math.sin(phi2);

        double lat = Math.atan2(z, Math.sqrt(x * x + y * y));
        double lon = Math.atan2(y, x);
        return new GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon));
    }

    /** The midpoint of the great circle between two points. */
    public static GeoPoint midpoint(GeoPoint from, GeoPoint to) {
        return intermediatePoint(from, to, 0.5);
    }

    /**
     * Signed perpendicular distance from {@code point} to the great circle through
     * {@code pathStart} and {@code pathEnd}. Positive means right of track. This is how a
     * plotted position is checked against the cleared route.
     */
    public static double crossTrackDistanceNm(GeoPoint point, GeoPoint pathStart, GeoPoint pathEnd) {
        double delta13 = angularDistanceRadians(pathStart, point);
        double theta13 = Math.toRadians(greatCircleInitialBearingDeg(pathStart, point));
        double theta12 = Math.toRadians(greatCircleInitialBearingDeg(pathStart, pathEnd));
        double xt = Math.asin(Math.sin(delta13) * Math.sin(theta13 - theta12));
        return Units.metresToNauticalMiles(xt * Ellipsoid.SPHERE.semiMajorAxisMetres());
    }

    /**
     * Distance from {@code pathStart} to the foot of the perpendicular dropped from
     * {@code point} onto the path. Combined with {@link #crossTrackDistanceNm} it locates a
     * position relative to a leg.
     */
    public static double alongTrackDistanceNm(GeoPoint point, GeoPoint pathStart, GeoPoint pathEnd) {
        double delta13 = angularDistanceRadians(pathStart, point);
        double xtRadians = Units.nauticalMilesToMetres(crossTrackDistanceNm(point, pathStart, pathEnd))
                / Ellipsoid.SPHERE.semiMajorAxisMetres();
        double cosXt = Math.cos(xtRadians);
        if (cosXt == 0.0) {
            return 0.0;
        }
        double at = Math.acos(clamp(Math.cos(delta13) / cosXt, -1.0, 1.0));
        double sign = Math.abs(Math.toRadians(greatCircleInitialBearingDeg(pathStart, point))
                - Math.toRadians(greatCircleInitialBearingDeg(pathStart, pathEnd))) > Math.PI / 2.0 ? -1.0 : 1.0;
        return sign * Units.metresToNauticalMiles(at * Ellipsoid.SPHERE.semiMajorAxisMetres());
    }

    /** Angular separation of two points, in radians. */
    public static double angularDistanceRadians(GeoPoint from, GeoPoint to) {
        double phi1 = from.latitudeRadians(), phi2 = to.latitudeRadians();
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        double sinHalfDPhi = Math.sin(dPhi / 2.0);
        double sinHalfDLambda = Math.sin(dLambda / 2.0);
        double h = sinHalfDPhi * sinHalfDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDLambda * sinHalfDLambda;
        return 2.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // --------------------------------------------------------------------- rhumb

    /**
     * Rhumb-line (constant-course) distance. Published SID/STAR courses and holding legs are
     * flown as rhumb lines over short distances, and a few ARINC 424 leg types are defined that
     * way, so route drawing needs both this and the great circle.
     */
    public static double rhumbDistanceNm(GeoPoint from, GeoPoint to) {
        double phi1 = from.latitudeRadians(), phi2 = to.latitudeRadians();
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        double dPsi = Math.log(Math.tan(Math.PI / 4.0 + phi2 / 2.0)
                / Math.tan(Math.PI / 4.0 + phi1 / 2.0));
        // q degenerates as the course approaches due east/west; fall back to cos(latitude).
        double q = Math.abs(dPsi) > 1e-12 ? dPhi / dPsi : Math.cos(phi1);
        double delta = Math.sqrt(dPhi * dPhi + q * q * dLambda * dLambda);
        return Units.metresToNauticalMiles(delta * Ellipsoid.SPHERE.semiMajorAxisMetres());
    }

    /** Constant true course of the rhumb line between two points, degrees in [0, 360). */
    public static double rhumbBearingDeg(GeoPoint from, GeoPoint to) {
        double phi1 = from.latitudeRadians(), phi2 = to.latitudeRadians();
        double dLambda = Math.toRadians(GeoPoint.normaliseLongitude(to.longitude() - from.longitude()));
        double dPsi = Math.log(Math.tan(Math.PI / 4.0 + phi2 / 2.0)
                / Math.tan(Math.PI / 4.0 + phi1 / 2.0));
        return normaliseBearing(Math.toDegrees(Math.atan2(dLambda, dPsi)));
    }

    // ------------------------------------------------------------------- bearings

    /** Wraps any bearing into [0, 360). */
    public static double normaliseBearing(double degrees) {
        double d = degrees % 360.0;
        return d < 0.0 ? d + 360.0 : d;
    }

    /** The reciprocal of a bearing. */
    public static double reciprocalBearing(double degrees) {
        return normaliseBearing(degrees + 180.0);
    }

    /**
     * Smallest signed turn from {@code fromBearing} to {@code toBearing}, in (-180, +180].
     * Positive is a right turn. Route legs whose turn exceeds a threshold are flagged on the OFP.
     */
    public static double bearingDifference(double fromBearing, double toBearing) {
        double diff = normaliseBearing(toBearing) - normaliseBearing(fromBearing);
        if (diff > 180.0) {
            diff -= 360.0;
        } else if (diff <= -180.0) {
            diff += 360.0;
        }
        return diff;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
