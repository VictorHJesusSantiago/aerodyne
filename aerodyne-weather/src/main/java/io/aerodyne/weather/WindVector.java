package io.aerodyne.weather;

import io.aerodyne.geo.Geodesy;

import java.util.Locale;

/**
 * A wind, in the meteorological convention: {@code directionDeg} is the direction the wind blows
 * <em>from</em>, measured as a true bearing.
 *
 * <p>That convention is the source of most sign errors in flight planning code, so the vector
 * components are exposed explicitly. {@link #eastwardKt()} and {@link #northwardKt()} give the
 * direction the air is <em>moving</em>, which is the reciprocal — a 270/50 wind blows towards
 * 090, so its eastward component is +50 kt.
 */
public record WindVector(double directionDeg, double speedKt) {

    /** No wind. Its direction is arbitrary; north is used so the record has a stable identity. */
    public static final WindVector CALM = new WindVector(0.0, 0.0);

    public WindVector {
        if (speedKt < 0.0) {
            throw new IllegalArgumentException("wind speed cannot be negative: " + speedKt);
        }
        if (Double.isNaN(directionDeg) || Double.isNaN(speedKt)) {
            throw new IllegalArgumentException("wind must not be NaN");
        }
        directionDeg = speedKt == 0.0 ? 0.0 : Geodesy.normaliseBearing(directionDeg);
    }

    public static WindVector of(double directionDeg, double speedKt) {
        return new WindVector(directionDeg, speedKt);
    }

    /**
     * Builds a wind from the components of air motion — eastward and northward in knots, the
     * way a GRIB2 file stores it as its {@code UGRD} and {@code VGRD} fields.
     */
    public static WindVector fromComponents(double eastwardKt, double northwardKt) {
        double speed = Math.hypot(eastwardKt, northwardKt);
        if (speed < 1e-9) {
            return CALM;
        }
        // atan2 gives the direction the air moves towards; the reported direction is its reciprocal.
        double towards = Math.toDegrees(Math.atan2(eastwardKt, northwardKt));
        return new WindVector(Geodesy.reciprocalBearing(towards), speed);
    }

    /** Eastward component of air motion, positive towards 090. */
    public double eastwardKt() {
        return -speedKt * Math.sin(Math.toRadians(directionDeg));
    }

    /** Northward component of air motion, positive towards 360. */
    public double northwardKt() {
        return -speedKt * Math.cos(Math.toRadians(directionDeg));
    }

    /** The direction the air is moving towards, which is what a drift calculation needs. */
    public double blowingTowardsDeg() {
        return Geodesy.reciprocalBearing(directionDeg);
    }

    /**
     * The component of this wind along a track, positive as a <em>tailwind</em>. A flight plan
     * quotes the reverse sign as the headwind component, which {@link #headwindComponentKt}
     * returns.
     */
    public double tailwindComponentKt(double trueCourseDeg) {
        return speedKt * -Math.cos(Math.toRadians(directionDeg - trueCourseDeg));
    }

    /** The component along a track, positive as a headwind. */
    public double headwindComponentKt(double trueCourseDeg) {
        return -tailwindComponentKt(trueCourseDeg);
    }

    /** The component across a track, positive from the left (drifting the aircraft right). */
    public double crosswindComponentKt(double trueCourseDeg) {
        return speedKt * -Math.sin(Math.toRadians(directionDeg - trueCourseDeg));
    }

    /**
     * The mean of several winds, taken over their components rather than their directions.
     * Averaging bearings directly is what makes a 350/40 and a 010/40 come out as a westerly.
     */
    public static WindVector mean(java.util.Collection<WindVector> winds) {
        if (winds.isEmpty()) {
            return CALM;
        }
        double east = 0.0;
        double north = 0.0;
        for (WindVector wind : winds) {
            east += wind.eastwardKt();
            north += wind.northwardKt();
        }
        return fromComponents(east / winds.size(), north / winds.size());
    }

    /**
     * Linear interpolation between two winds, again through their components. {@code f == 0}
     * gives this wind and {@code f == 1} gives {@code other}.
     */
    public WindVector interpolateTo(WindVector other, double f) {
        double clamped = Math.max(0.0, Math.min(1.0, f));
        return fromComponents(
                eastwardKt() + (other.eastwardKt() - eastwardKt()) * clamped,
                northwardKt() + (other.northwardKt() - northwardKt()) * clamped);
    }

    /** {@code 27050}, the five-digit form a wind is written in on an OFP wind column. */
    public String toOfpString() {
        return String.format(Locale.ROOT, "%03d%02d",
                Math.round(directionDeg) % 360, Math.min(99, Math.round(speedKt)));
    }

    @Override
    public String toString() {
        return speedKt == 0.0
                ? "CALM"
                : String.format(Locale.ROOT, "%03d/%.0fkt", Math.round(directionDeg) % 360, speedKt);
    }
}
