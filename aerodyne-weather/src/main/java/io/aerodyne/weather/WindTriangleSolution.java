package io.aerodyne.weather;

/**
 * The solved wind triangle for one leg.
 *
 * @param trueHeadingDeg          the heading to fly to hold the track
 * @param groundSpeedKt           the resulting ground speed; can be negative in a wind stronger
 *                                than the airspeed, which means the leg cannot be flown
 * @param windCorrectionAngleDeg  the drift correction applied, positive to the right
 * @param headwindComponentKt     wind along the track, positive as a headwind
 * @param crosswindComponentKt    wind across the track, positive from the left
 */
public record WindTriangleSolution(double trueHeadingDeg,
                                   double groundSpeedKt,
                                   double windCorrectionAngleDeg,
                                   double headwindComponentKt,
                                   double crosswindComponentKt) {

    /** True when the leg makes progress at all; a ground speed at or below zero does not. */
    public boolean isFlyable() {
        return groundSpeedKt > 0.0;
    }

    /** Time to cover a distance at this ground speed, in hours. */
    public double timeHours(double distanceNm) {
        if (!isFlyable()) {
            throw new IllegalStateException(
                    "leg is not flyable: ground speed is " + Math.round(groundSpeedKt) + " kt");
        }
        return distanceNm / groundSpeedKt;
    }

    /** Time to cover a distance at this ground speed, in minutes, as an OFP prints it. */
    public double timeMinutes(double distanceNm) {
        return timeHours(distanceNm) * 60.0;
    }
}
