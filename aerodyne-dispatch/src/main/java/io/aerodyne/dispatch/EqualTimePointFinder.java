package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;

import java.time.Instant;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

/**
 * Finds equal-time points along a route.
 *
 * <p>The quantity searched is the difference between the time to the first aerodrome and the
 * time to the second, as a function of distance along the route. At the start of the route that
 * difference is negative — the first aerodrome is behind you and close — and by the end it is
 * positive. Somewhere in between it crosses zero, and that crossing is the equal-time point.
 *
 * <p>The function is continuous and, for two aerodromes on opposite sides of the route, is
 * monotone increasing, so bisection finds the crossing reliably in a couple of dozen
 * evaluations. Bisection rather than something faster is deliberate: each evaluation samples the
 * wind, which is not smooth, and a method that assumed smoothness could walk off a jet stream
 * boundary and converge on the wrong point.
 *
 * <p>Where the difference does not change sign across the route there is no equal-time point on
 * it — one aerodrome is nearer in time for the whole way — and the finder says so rather than
 * returning an endpoint dressed up as a crossing.
 */
public final class EqualTimePointFinder {

    /** Convergence tolerance: half a minute is finer than the wind sampling justifies. */
    private static final double TOLERANCE_MINUTES = 0.5;
    /** Bisection halves the interval each time, so this covers any route to within metres. */
    private static final int MAX_ITERATIONS = 60;

    private final RoutePath path;
    private final DiversionCalculator diversions;

    public EqualTimePointFinder(RoutePath path, DiversionCalculator diversions) {
        this.path = path;
        this.diversions = diversions;
    }

    /**
     * The equal-time point between two aerodromes.
     *
     * @param first        the aerodrome that is nearer in time early in the route
     * @param firstIcao    its identifier
     * @param second       the aerodrome that is nearer in time later on
     * @param secondIcao   its identifier
     * @param offBlocks    when the flight starts, so the wind is sampled at the right time
     * @param groundSpeedKt the planned ground speed, used to work out when each point is reached
     * @return the point, or empty when the route holds no crossing
     */
    public Optional<EqualTimePoint> between(GeoPoint first, String firstIcao,
                                            GeoPoint second, String secondIcao,
                                            Instant offBlocks, double groundSpeedKt) {
        DoubleUnaryOperator difference = along -> {
            Instant at = timeAt(along, offBlocks, groundSpeedKt);
            GeoPoint here = path.pointAtNm(along);
            return diversions.timeMinutes(here, first, at) - diversions.timeMinutes(here, second, at);
        };

        double low = 0.0;
        double high = path.totalDistanceNm();
        double atLow = difference.applyAsDouble(low);
        double atHigh = difference.applyAsDouble(high);

        if (!Double.isFinite(atLow) || !Double.isFinite(atHigh)) {
            return Optional.empty();
        }
        if (atLow > 0.0 == atHigh > 0.0) {
            // No sign change: one aerodrome is nearer in time for the whole route.
            return Optional.empty();
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double middle = (low + high) / 2.0;
            double atMiddle = difference.applyAsDouble(middle);
            if (Math.abs(atMiddle) < TOLERANCE_MINUTES) {
                return Optional.of(build(middle, first, firstIcao, second, secondIcao,
                        offBlocks, groundSpeedKt));
            }
            if (atMiddle > 0.0 == atLow > 0.0) {
                low = middle;
                atLow = atMiddle;
            } else {
                high = middle;
            }
        }
        return Optional.of(build((low + high) / 2.0, first, firstIcao, second, secondIcao,
                offBlocks, groundSpeedKt));
    }

    /**
     * The critical point of the route: where the departure and the destination are the same
     * flying time away. It is the same computation, and it is the point past which turning back
     * stops being the quicker option.
     */
    public Optional<EqualTimePoint> criticalPoint(String departureIcao, String destinationIcao,
                                                  Instant offBlocks, double groundSpeedKt) {
        return between(path.start(), departureIcao, path.end(), destinationIcao,
                offBlocks, groundSpeedKt);
    }

    private EqualTimePoint build(double along, GeoPoint first, String firstIcao,
                                 GeoPoint second, String secondIcao,
                                 Instant offBlocks, double groundSpeedKt) {
        GeoPoint position = path.pointAtNm(along);
        Instant at = timeAt(along, offBlocks, groundSpeedKt);
        return new EqualTimePoint(position, along, at,
                diversions.to(position, first, at),
                diversions.to(position, second, at),
                firstIcao, secondIcao);
    }

    /**
     * When the flight reaches a point on the route. A single planned ground speed rather than
     * the integrated profile: the equal-time point moves by a mile or so for an hour's error in
     * the timing, and the wind forecast does not resolve an hour anyway.
     */
    private static Instant timeAt(double distanceAlongNm, Instant offBlocks, double groundSpeedKt) {
        if (groundSpeedKt <= 0.0) {
            return offBlocks;
        }
        return offBlocks.plusSeconds(Math.round(distanceAlongNm / groundSpeedKt * 3600.0));
    }
}
