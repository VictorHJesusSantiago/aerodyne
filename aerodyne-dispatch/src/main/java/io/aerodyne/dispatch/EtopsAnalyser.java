package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.Airport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assesses a route against an ETOPS approval.
 *
 * <p>The route is sampled at a fixed interval and, at each sample, the flying time to the
 * nearest adequate aerodrome is computed against the forecast wind. From that profile the entry
 * and exit points and the critical point fall out.
 *
 * <p>Sampling rather than solving is the honest approach here. The diversion time along a route
 * is not a smooth function — it has a kink wherever the nearest aerodrome changes, which is
 * exactly where the maximum tends to sit — so a calculus method would need to know the
 * changeover points before it could find the maximum, and those are the equal-time points,
 * which are found by search anyway. A twenty-mile sample resolves the maximum to well under a
 * minute of diversion time, which is finer than the wind forecast justifies.
 *
 * <p>The equal-time points between successive aerodromes are computed exactly, because those go
 * on the OFP and are briefed as decision points rather than merely used as intermediate values.
 */
public final class EtopsAnalyser {

    /** The ordinary limit for a twin: sixty minutes from an adequate aerodrome. */
    public static final int DEFAULT_THRESHOLD_MINUTES = 60;
    /** How finely the route is scanned. */
    public static final double DEFAULT_SAMPLE_INTERVAL_NM = 20.0;

    private final DiversionCalculator diversions;
    private final int thresholdMinutes;
    private final double sampleIntervalNm;

    public EtopsAnalyser(DiversionCalculator diversions) {
        this(diversions, DEFAULT_THRESHOLD_MINUTES, DEFAULT_SAMPLE_INTERVAL_NM);
    }

    public EtopsAnalyser(DiversionCalculator diversions, int thresholdMinutes, double sampleIntervalNm) {
        this.diversions = diversions;
        this.thresholdMinutes = thresholdMinutes;
        this.sampleIntervalNm = sampleIntervalNm;
    }

    /**
     * Assesses a route.
     *
     * @param path              the route
     * @param adequateAerodromes the aerodromes a diversion could actually be made to; screening
     *                          them for runway length, weather and closures happens before this
     * @param approvedMinutes   the operator's approved diversion time
     * @param offBlocks         when the flight starts
     * @param groundSpeedKt     the planned ground speed, for placing the samples in time
     * @throws IllegalArgumentException when no adequate aerodrome is offered, since a route with
     *                                  nowhere to divert to cannot be assessed at all
     */
    public EtopsAnalysis analyse(RoutePath path, List<Airport> adequateAerodromes,
                                 int approvedMinutes, Instant offBlocks, double groundSpeedKt) {
        return analyse(path, adequateAerodromes, approvedMinutes, offBlocks, groundSpeedKt, false);
    }

    /**
     * The same assessment, told whether the aerodromes it was given had been screened against
     * their forecasts. The assessment does not screen them itself — that needs a TAF feed it has
     * no business holding — but it does have to carry the answer, because a route assessed
     * against merely adequate aerodromes has not been shown to be flyable.
     */
    public EtopsAnalysis analyse(RoutePath path, List<Airport> adequateAerodromes,
                                 int approvedMinutes, Instant offBlocks, double groundSpeedKt,
                                 boolean weatherScreened) {
        if (adequateAerodromes.isEmpty()) {
            throw new IllegalArgumentException(
                    "a route cannot be assessed with no adequate aerodrome to divert to");
        }

        List<RoutePath.Sample> samples = path.sample(sampleIntervalNm);
        GeoPoint entry = null;
        GeoPoint exit = null;
        GeoPoint worstPoint = path.start();
        double worstMinutes = -1.0;
        String worstNearest = adequateAerodromes.get(0).icaoCode();

        for (RoutePath.Sample sample : samples) {
            Instant at = timeAt(sample.distanceAlongNm(), offBlocks, groundSpeedKt);
            Nearest nearest = nearestAerodrome(sample.position(), adequateAerodromes, at);

            if (nearest.minutes() > worstMinutes) {
                worstMinutes = nearest.minutes();
                worstPoint = sample.position();
                worstNearest = nearest.icao();
            }
            if (nearest.minutes() > thresholdMinutes) {
                if (entry == null) {
                    entry = sample.position();
                }
                // Keep moving the exit forward; the last sample outside the threshold area is
                // where the route comes back inside it.
                exit = sample.position();
            }
        }

        List<EqualTimePoint> equalTimePoints =
                equalTimePoints(path, adequateAerodromes, offBlocks, groundSpeedKt);

        return new EtopsAnalysis(approvedMinutes, thresholdMinutes,
                Optional.ofNullable(entry), Optional.ofNullable(exit),
                worstPoint, worstMinutes, worstNearest,
                adequateAerodromes.stream().map(Airport::icaoCode).toList(),
                equalTimePoints, weatherScreened);
    }

    /**
     * The equal-time point between each consecutive pair of adequate aerodromes, in the order
     * given. Pairs with no crossing on the route contribute nothing, which is the usual case
     * for aerodromes that are both behind or both ahead of the whole route.
     */
    private List<EqualTimePoint> equalTimePoints(RoutePath path, List<Airport> aerodromes,
                                                 Instant offBlocks, double groundSpeedKt) {
        EqualTimePointFinder finder = new EqualTimePointFinder(path, diversions);
        List<EqualTimePoint> points = new ArrayList<>();
        for (int i = 0; i < aerodromes.size() - 1; i++) {
            Airport first = aerodromes.get(i);
            Airport second = aerodromes.get(i + 1);
            finder.between(first.position(), first.icaoCode(),
                            second.position(), second.icaoCode(), offBlocks, groundSpeedKt)
                    .ifPresent(points::add);
        }
        return points;
    }

    /** The adequate aerodrome nearest in time to a position. */
    private Nearest nearestAerodrome(GeoPoint position, List<Airport> aerodromes, Instant at) {
        String icao = aerodromes.get(0).icaoCode();
        double best = Double.POSITIVE_INFINITY;
        for (Airport aerodrome : aerodromes) {
            double minutes = diversions.timeMinutes(position, aerodrome.position(), at);
            if (minutes < best) {
                best = minutes;
                icao = aerodrome.icaoCode();
            }
        }
        return new Nearest(icao, best);
    }

    private static Instant timeAt(double distanceAlongNm, Instant offBlocks, double groundSpeedKt) {
        if (groundSpeedKt <= 0.0) {
            return offBlocks;
        }
        return offBlocks.plusSeconds(Math.round(distanceAlongNm / groundSpeedKt * 3600.0));
    }

    private record Nearest(String icao, double minutes) {
    }
}
