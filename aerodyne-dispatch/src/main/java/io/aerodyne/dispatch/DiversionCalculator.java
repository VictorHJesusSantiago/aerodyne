package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.weather.AtmosphericConditions;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindTriangle;
import io.aerodyne.weather.WindVector;

import java.time.Instant;

/**
 * How long it takes to divert from a point to an aerodrome.
 *
 * <p>This is the primitive every ETOPS and equal-time computation is built on, and the wind is
 * what makes it non-trivial. A diversion of 400 nm is 60 minutes in still air and can be 75
 * minutes into a jet stream — which is the difference between a route being inside its approved
 * diversion time and outside it.
 *
 * <p>The wind is sampled at the midpoint of the diversion leg, at the diversion altitude. That
 * is one sample rather than an integration, and it is the right trade here: the diversion is
 * hypothetical, it is flown at a single level, and the leg is short enough that a single
 * midpoint sample is within a minute or two of an integrated one. A diversion that would need
 * more precision than that is one whose margin is too thin to dispatch on anyway.
 *
 * <p>The altitude is the engine-out drift-down level, not the cruise level. An aeroplane that
 * has lost an engine cannot hold FL350, and planning a diversion at cruise speed and cruise
 * altitude would understate the time it actually takes.
 */
public final class DiversionCalculator {

    private final WeatherModel weather;
    private final double diversionSpeedKt;
    private final double diversionAltitudeFt;

    /**
     * @param weather             where the wind comes from
     * @param diversionSpeedKt    the true airspeed the diversion is planned at
     * @param diversionAltitudeFt the level it is planned at — the engine-out ceiling, not the
     *                            cruise level
     */
    public DiversionCalculator(WeatherModel weather, double diversionSpeedKt, double diversionAltitudeFt) {
        if (diversionSpeedKt <= 0.0) {
            throw new IllegalArgumentException("diversion speed must be positive");
        }
        this.weather = weather;
        this.diversionSpeedKt = diversionSpeedKt;
        this.diversionAltitudeFt = diversionAltitudeFt;
    }

    public double diversionSpeedKt() {
        return diversionSpeedKt;
    }

    public double diversionAltitudeFt() {
        return diversionAltitudeFt;
    }

    /** A diversion from one point to another: how far, how fast, how long. */
    public Diversion to(GeoPoint from, GeoPoint destination, Instant at) {
        double distance = Geodesy.distanceNm(from, destination);
        if (distance <= 0.0) {
            return new Diversion(from, destination, 0.0, 0.0, diversionSpeedKt, WindVector.CALM);
        }
        double course = Geodesy.initialBearingDeg(from, destination);
        GeoPoint midpoint = Geodesy.midpoint(from, destination);
        AtmosphericConditions air = weather.at(midpoint, diversionAltitudeFt, at);
        double groundSpeed = groundSpeed(course, air.wind());

        double timeMinutes = groundSpeed <= 0.0
                ? Double.POSITIVE_INFINITY
                : distance / groundSpeed * 60.0;
        return new Diversion(from, destination, distance, timeMinutes, groundSpeed, air.wind());
    }

    /** Just the time, which is what the equal-time search compares. */
    public double timeMinutes(GeoPoint from, GeoPoint destination, Instant at) {
        return to(from, destination, at).timeMinutes();
    }

    /**
     * The still-air distance the aircraft covers in a given diversion time. This is the radius
     * of the circle drawn around each adequate aerodrome when the route is first screened,
     * before the wind-corrected times are computed for the points that matter.
     */
    public double rangeNm(double minutes) {
        return diversionSpeedKt * minutes / 60.0;
    }

    /**
     * Ground speed towards an aerodrome. A crosswind too strong for any heading to hold the
     * track is not a reason to refuse an answer here — the aircraft would still get there, just
     * not on that exact track — so the along-track component is used instead.
     */
    private double groundSpeed(double courseDeg, WindVector wind) {
        try {
            return WindTriangle.groundSpeedKt(courseDeg, diversionSpeedKt, wind);
        } catch (WindTriangle.UnsolvableWindException windTooStrong) {
            return diversionSpeedKt + wind.tailwindComponentKt(courseDeg);
        }
    }

    /**
     * One hypothetical diversion.
     *
     * @param from          where it starts
     * @param destination   where it goes
     * @param distanceNm    how far
     * @param timeMinutes   how long, against the wind; infinite when the wind makes it unflyable
     * @param groundSpeedKt the speed made good
     * @param wind          the wind used, so the figure can be checked
     */
    public record Diversion(GeoPoint from, GeoPoint destination, double distanceNm,
                            double timeMinutes, double groundSpeedKt, WindVector wind) {

        /** True when the diversion can actually be flown against this wind. */
        public boolean isFlyable() {
            return Double.isFinite(timeMinutes);
        }

        /** True when the diversion fits inside an approved diversion time. */
        public boolean fitsWithinMinutes(double approvedMinutes) {
            return isFlyable() && timeMinutes <= approvedMinutes;
        }
    }
}
