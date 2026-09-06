package io.aerodyne.dispatch;

import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.weather.metar.TafReport;
import io.aerodyne.dispatch.notam.Notam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the aerodromes an ETOPS diversion could actually be made to, along a route.
 *
 * <p>"Adequate" and "suitable" are two different words in this part of the regulations and the
 * difference is the whole point of this class. An aerodrome is <em>adequate</em> if the aircraft
 * can physically use it — the runway is long enough and it is not closed. It is <em>suitable</em>
 * if it is also forecast, at the time the flight might need it, to be usable: a 12 000 ft runway
 * under a 100 ft ceiling is adequate and unsuitable, and planning a diversion to it is planning
 * a diversion to somewhere the aircraft cannot land.
 *
 * <p>Screening on runway length alone — which is all a route assessment can do with no forecast
 * in hand — therefore overstates how close the route is to a diversion, and it overstates it in
 * the unsafe direction. So this class screens on the forecast where it has one, and says plainly
 * which of the two screenings it managed. The caller can then fall back, but it cannot fall back
 * without knowing it did.
 *
 * <p>The time each aerodrome is assessed at is not the flight's arrival time at its destination.
 * It is the time the aircraft passes the point it would divert from, plus the diversion itself —
 * which for a 180-minute approval is three hours later, well into a different part of the TAF.
 */
public final class EtopsAerodromeScreen {

    /** How far apart the route is sampled when looking for aerodromes near it. */
    public static final double DEFAULT_SAMPLE_INTERVAL_NM = 200.0;
    /** How far either side of the route to look. */
    public static final double DEFAULT_SEARCH_RADIUS_NM = 400.0;
    /** How many aerodromes to consider at each sample, nearest first. */
    private static final int CANDIDATES_PER_SAMPLE = 5;

    private final NavigationDatabase database;
    private final AlternateSelector selector;
    private final double sampleIntervalNm;
    private final double searchRadiusNm;

    public EtopsAerodromeScreen(NavigationDatabase database) {
        this(database, DEFAULT_SAMPLE_INTERVAL_NM, DEFAULT_SEARCH_RADIUS_NM);
    }

    public EtopsAerodromeScreen(NavigationDatabase database, double sampleIntervalNm,
                                double searchRadiusNm) {
        this.database = database;
        this.selector = new AlternateSelector(database);
        this.sampleIntervalNm = sampleIntervalNm;
        this.searchRadiusNm = searchRadiusNm;
    }

    /**
     * What the screening found.
     *
     * @param adequate        the aerodromes to assess the route against, in route order
     * @param rejected        the ones that were considered and refused, with their reasons
     * @param weatherScreened true when the forecast was actually applied; false when the screen
     *                        fell back to runway length because nothing usable was forecast
     */
    public record Result(List<Airport> adequate, List<AlternateCandidate> rejected,
                         boolean weatherScreened) {

        public Result {
            adequate = List.copyOf(adequate);
            rejected = List.copyOf(rejected);
        }

        public boolean isEmpty() {
            return adequate.isEmpty();
        }

        /** Why an aerodrome the route passes was not counted, for the OFP's remarks. */
        public List<String> reasons() {
            return rejected.stream()
                    .map(candidate -> candidate.airport().icaoCode() + ": "
                            + String.join(", ", candidate.rejections()))
                    .toList();
        }
    }

    /**
     * Screens the aerodromes along a route.
     *
     * @param path          the route
     * @param criteria      the en-route alternate minima to apply
     * @param forecasts     the TAFs available, by ICAO code
     * @param notams        the NOTAMs to check against
     * @param offBlocks     when the flight starts
     * @param groundSpeedKt the planned ground speed, for placing the samples in time
     * @param diversionMinutes how long a diversion takes, which is how far past a sample the
     *                      aerodrome has to be forecast usable
     */
    public Result along(RoutePath path, AlternateCriteria criteria, Map<String, TafReport> forecasts,
                        List<Notam> notams, Instant offBlocks, double groundSpeedKt,
                        int diversionMinutes) {
        Map<String, Airport> adequate = new LinkedHashMap<>();
        Map<String, AlternateCandidate> rejected = new LinkedHashMap<>();
        Map<String, Airport> byRunwayOnly = new LinkedHashMap<>();

        for (RoutePath.Sample sample : path.sample(sampleIntervalNm)) {
            Instant divertingAt = timeAt(sample.distanceAlongNm(), offBlocks, groundSpeedKt)
                    .plusSeconds(diversionMinutes * 60L);

            List<Airport> nearby = database.airportsWithin(
                    sample.position(), searchRadiusNm, CANDIDATES_PER_SAMPLE);
            for (Airport airport : nearby) {
                if (airport.hasRunwayAtLeast(criteria.minimumRunwayFt())) {
                    byRunwayOnly.putIfAbsent(airport.icaoCode(), airport);
                }
                if (adequate.containsKey(airport.icaoCode())) {
                    continue;
                }
                AlternateCandidate assessment = assess(
                        airport, sample, criteria, forecasts, notams, divertingAt);
                if (assessment.isUsable()) {
                    adequate.put(airport.icaoCode(), airport);
                    rejected.remove(airport.icaoCode());
                } else {
                    // Keep the first refusal: it is the earliest point on the route at which
                    // the aerodrome was wanted, which is the one worth explaining.
                    rejected.putIfAbsent(airport.icaoCode(), assessment);
                }
            }
        }

        if (!adequate.isEmpty()) {
            return new Result(List.copyOf(adequate.values()), sorted(rejected.values()), true);
        }
        // Nothing along the route is forecast usable — usually because no TAF was loaded for
        // any of them, which is the ordinary case for mid-ocean aerodromes. Fall back to what
        // can be established, and say so, rather than declaring a route unassessable.
        return new Result(List.copyOf(byRunwayOnly.values()), sorted(rejected.values()), false);
    }

    /**
     * Screens an aerodrome the way {@link AlternateSelector} does, but measured from the route
     * rather than from the destination, so the distance limit does not silently refuse an
     * aerodrome for being far from an airport nobody is diverting to.
     */
    private AlternateCandidate assess(Airport airport, RoutePath.Sample sample,
                                      AlternateCriteria criteria, Map<String, TafReport> forecasts,
                                      List<Notam> notams, Instant divertingAt) {
        return selector.screen(sample.position(), criteria.withinNm(searchRadiusNm),
                        forecasts, notams, divertingAt).stream()
                .filter(candidate -> candidate.airport().icaoCode().equals(airport.icaoCode()))
                .findFirst()
                .orElseGet(() -> new AlternateCandidate(airport, 0.0, 0.0, null,
                        List.of("outside the search radius")));
    }

    private static List<AlternateCandidate> sorted(java.util.Collection<AlternateCandidate> values) {
        List<AlternateCandidate> list = new ArrayList<>(values);
        list.sort(Comparator.comparing(candidate -> candidate.airport().icaoCode()));
        return list;
    }

    private static Instant timeAt(double distanceAlongNm, Instant offBlocks, double groundSpeedKt) {
        if (groundSpeedKt <= 0.0) {
            return offBlocks;
        }
        return offBlocks.plusSeconds(Math.round(distanceAlongNm / groundSpeedKt * 3600.0));
    }
}
