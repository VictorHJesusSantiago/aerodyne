package io.aerodyne.weather;

import io.aerodyne.geo.Geodesy;

/**
 * The wind triangle: what heading to fly and what ground speed results, given a desired track,
 * a true airspeed and the wind.
 *
 * <p>Solved as vectors rather than with the law of sines. The two are equivalent, but the
 * vector form has no quadrant ambiguity to get wrong and the failure case — a wind whose
 * crosswind component exceeds the airspeed, so no heading holds the track — falls out of it
 * as an unsolvable equation rather than as a silently wrong answer.
 */
public final class WindTriangle {

    private WindTriangle() {
    }

    /**
     * Solves for the heading that holds {@code trueCourseDeg} and the ground speed that results.
     *
     * @param trueCourseDeg the track to be made good, true
     * @param trueAirspeedKt the aircraft's true airspeed
     * @param wind          the wind at the altitude being flown
     * @throws IllegalArgumentException if the airspeed is not positive
     * @throws UnsolvableWindException  if the crosswind exceeds the true airspeed, so the track
     *                                  cannot be held at all
     */
    public static WindTriangleSolution solve(double trueCourseDeg, double trueAirspeedKt, WindVector wind) {
        if (trueAirspeedKt <= 0.0) {
            throw new IllegalArgumentException("true airspeed must be positive: " + trueAirspeedKt);
        }
        // Positive from the left, so it drifts the aircraft right and needs a right correction.
        double crosswind = wind.crosswindComponentKt(trueCourseDeg);
        double tailwind = wind.tailwindComponentKt(trueCourseDeg);

        double sinCorrection = crosswind / trueAirspeedKt;
        if (Math.abs(sinCorrection) > 1.0) {
            throw new UnsolvableWindException(trueCourseDeg, trueAirspeedKt, wind);
        }
        double windCorrectionAngleDeg = Math.toDegrees(Math.asin(sinCorrection));
        double groundSpeedKt = trueAirspeedKt * Math.cos(Math.toRadians(windCorrectionAngleDeg)) + tailwind;

        return new WindTriangleSolution(
                Geodesy.normaliseBearing(trueCourseDeg + windCorrectionAngleDeg),
                groundSpeedKt,
                windCorrectionAngleDeg,
                -tailwind,
                crosswind);
    }

    /**
     * Ground speed alone, for the inner loop of the fuel integration where the heading is not
     * needed. Equivalent to {@code solve(...).groundSpeedKt()}.
     */
    public static double groundSpeedKt(double trueCourseDeg, double trueAirspeedKt, WindVector wind) {
        return solve(trueCourseDeg, trueAirspeedKt, wind).groundSpeedKt();
    }

    /** Raised when no heading holds the requested track against the given wind. */
    public static final class UnsolvableWindException extends RuntimeException {

        private final double trueCourseDeg;
        private final double trueAirspeedKt;
        private final transient WindVector wind;

        UnsolvableWindException(double trueCourseDeg, double trueAirspeedKt, WindVector wind) {
            super("no heading holds a track of " + Math.round(trueCourseDeg) + " at "
                    + Math.round(trueAirspeedKt) + " kt against " + wind
                    + ": the crosswind exceeds the airspeed");
            this.trueCourseDeg = trueCourseDeg;
            this.trueAirspeedKt = trueAirspeedKt;
            this.wind = wind;
        }

        public double trueCourseDeg() {
            return trueCourseDeg;
        }

        public double trueAirspeedKt() {
            return trueAirspeedKt;
        }

        public WindVector wind() {
            return wind;
        }
    }
}
