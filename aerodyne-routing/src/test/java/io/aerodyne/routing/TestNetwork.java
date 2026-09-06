package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Waypoint;

import java.util.List;

/**
 * A small navigation database for the routing tests.
 *
 * <p>Six fixes on a diagonal from north-west to south-east, with a detour to the north, and four
 * airways over them chosen to exercise the cases the search has to get right: a direct chain, a
 * longer alternative, a low-level airway that must not appear at cruise levels, and a one-way
 * airway that must not be flown backwards.
 *
 * <pre>
 *                NORTH
 *              /       \        UM2 (long way round)
 *   ALFA -- BRAVO -- CHARL -- DELTA -- ECHO      UM1 (the direct chain)
 *              \_______/                          UL9 (low level only)
 *                       \______/                  UN7 (one way, east)
 * </pre>
 */
final class TestNetwork {

    static final GeoPoint ALFA = GeoPoint.of(51.0, -1.0);
    static final GeoPoint BRAVO = GeoPoint.of(50.0, 0.0);
    static final GeoPoint CHARL = GeoPoint.of(49.0, 1.0);
    static final GeoPoint DELTA = GeoPoint.of(48.0, 2.0);
    static final GeoPoint ECHO = GeoPoint.of(47.0, 3.0);
    static final GeoPoint NORTH = GeoPoint.of(53.0, 1.0);

    static final GeoPoint EGXX = GeoPoint.of(51.2, -1.2);
    static final GeoPoint EGYY = GeoPoint.of(46.8, 3.2);

    private TestNetwork() {
    }

    static NavigationDatabase build() {
        return NavigationDatabase.builder()
                .cycle(new AiracCycle(2024, 13))
                .waypoints(List.of(
                        Waypoint.enroute("ALFA", "EG", ALFA),
                        Waypoint.enroute("BRAVO", "EG", BRAVO),
                        Waypoint.enroute("CHARL", "EG", CHARL),
                        Waypoint.enroute("DELTA", "EG", DELTA),
                        Waypoint.enroute("ECHO", "EG", ECHO),
                        Waypoint.enroute("NORTH", "EG", NORTH),
                        // A repeated identifier on the far side of the world, so that the tests
                        // exercise positional resolution rather than a unique lookup.
                        Waypoint.enroute("CHARL", "SB", GeoPoint.of(-23.0, -46.0))))
                .airports(List.of(
                        airport("EGXX", EGXX),
                        airport("EGYY", EGYY)))
                .airways(List.of(
                        airway("UM1", AirwayLevel.HIGH, DirectionRestriction.BOTH,
                                24_000, 46_000, "ALFA", "BRAVO", "CHARL", "DELTA", "ECHO"),
                        airway("UM2", AirwayLevel.HIGH, DirectionRestriction.BOTH,
                                24_000, 46_000, "ALFA", "NORTH", "DELTA"),
                        airway("UL9", AirwayLevel.LOW, DirectionRestriction.BOTH,
                                3_000, 19_000, "BRAVO", "CHARL"),
                        airway("UN7", AirwayLevel.HIGH, DirectionRestriction.FORWARD,
                                24_000, 46_000, "CHARL", "DELTA")))
                .build();
    }

    private static Airport airport(String icao, GeoPoint position) {
        return new Airport(icao, null, position, 100, 6000, 10_000, "C", icao, List.of());
    }

    private static Airway airway(String ident, AirwayLevel level, DirectionRestriction direction,
                                 int minimumAltitudeFt, int maximumAltitudeFt, String... fixes) {
        List<AirwayPoint> points = new java.util.ArrayList<>(fixes.length);
        for (int i = 0; i < fixes.length; i++) {
            boolean last = i == fixes.length - 1;
            points.add(new AirwayPoint((i + 1) * 10, fixes[i], "EG", direction, level,
                    minimumAltitudeFt, maximumAltitudeFt, last ? " EE " : "    "));
        }
        return new Airway(ident, points);
    }
}
