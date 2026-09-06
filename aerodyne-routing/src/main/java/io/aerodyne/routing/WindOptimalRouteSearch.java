package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Fix;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindTriangle;
import io.aerodyne.weather.WindVector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds the <em>quickest</em> route through the airway network, rather than the shortest one.
 *
 * <p>{@link AirwayRouteSearch} minimises distance, and on a sector with any real wind that is
 * the wrong thing to minimise. A route two hundred miles longer down the middle of a jet stream
 * arrives sooner and burns less than the direct one across it. This class is the same A* over
 * the same {@link AirwayGraph}, with two things changed, and the two have to change together.
 *
 * <p><b>The edge cost becomes time.</b> A leg's cost is its distance divided by the ground speed
 * the aircraft would make along it, which is the wind triangle solved against the forecast at
 * that leg's midpoint and at the level being flown. The wind is sampled at the time the aircraft
 * would be there — the cost accumulated to a node <em>is</em> the time to reach it, so that time
 * is already in hand — which means a long sector sees the forecast move under it, as it does.
 *
 * <p><b>The heuristic becomes time too, and that is the delicate part.</b> A* only returns the
 * true optimum while its heuristic never overestimates what remains. The straight-line distance
 * is admissible against a distance cost; divided by the aircraft's airspeed it would not be
 * admissible against a time cost, because a tailwind can carry the aircraft over the ground
 * faster than it flies through the air. So the remaining distance is divided by the fastest
 * ground speed physically available anywhere in the forecast — the airspeed plus the strongest
 * wind the model holds, from {@link WeatherModel#strongestWindKt()}. No real leg can beat that,
 * so the estimate is never too large, and the first route the search completes is the quickest.
 *
 * <p>The cost of that guarantee is work: a loose bound makes the heuristic weak and the search
 * expands more of the graph than a distance search would. That is the trade being made, and it
 * is the right way round — a fast search that returns the second-best route is worth nothing.
 */
public final class WindOptimalRouteSearch {

    /** How many nodes the search will expand before giving up. */
    private static final int DEFAULT_NODE_LIMIT = 200_000;

    private final AirwayGraph graph;
    private final WeatherModel weather;
    private final double cruiseAltitudeFt;
    private final double trueAirspeedKt;
    private final Instant departureTime;
    private final int nodeLimit;

    /**
     * @param graph            the network, already built for the level being flown
     * @param weather          the forecast to fly against
     * @param cruiseAltitudeFt the level, which is what the wind is sampled at
     * @param trueAirspeedKt   the cruise true airspeed; constant here, because a search that
     *                         tracked the weight down would have to re-cost every edge for every
     *                         path reaching it, and the ordering of routes barely moves for it
     * @param departureTime    when the aircraft passes the first fix
     */
    public WindOptimalRouteSearch(AirwayGraph graph, WeatherModel weather, double cruiseAltitudeFt,
                                  double trueAirspeedKt, Instant departureTime) {
        this(graph, weather, cruiseAltitudeFt, trueAirspeedKt, departureTime, DEFAULT_NODE_LIMIT);
    }

    public WindOptimalRouteSearch(AirwayGraph graph, WeatherModel weather, double cruiseAltitudeFt,
                                  double trueAirspeedKt, Instant departureTime, int nodeLimit) {
        if (trueAirspeedKt <= 0.0) {
            throw new IllegalArgumentException("true airspeed must be positive: " + trueAirspeedKt);
        }
        this.graph = graph;
        this.weather = weather;
        this.cruiseAltitudeFt = cruiseAltitudeFt;
        this.trueAirspeedKt = trueAirspeedKt;
        this.departureTime = departureTime;
        this.nodeLimit = nodeLimit;
    }

    /**
     * The result of a search.
     *
     * @param elements      the route, starting with the origin fix
     * @param distanceNm    its total length, which is no longer what was minimised
     * @param timeMinutes   the time to fly it against the forecast, which is
     * @param nodesExpanded how much work the search did
     */
    public record Result(List<RouteElement> elements, double distanceNm, double timeMinutes,
                         int nodesExpanded) {

        public Result {
            elements = List.copyOf(elements);
        }

        public boolean found() {
            return !elements.isEmpty();
        }

        /** Average ground speed over the route, which is how a crew reads a wind-routed plan. */
        public double averageGroundSpeedKt() {
            return timeMinutes <= 0.0 ? 0.0 : distanceNm / (timeMinutes / 60.0);
        }

        /** The airways the route uses, in order, without repeats. */
        public List<String> airways() {
            return elements.stream()
                    .map(RouteElement::viaAirway)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }

        public String toRouteString() {
            return new ExpandedRoute("", elements, List.of()).toRouteString();
        }
    }

    /** Searches from one fix to another. Returns a result with no elements when none exists. */
    public Result search(Fix from, Fix to) {
        if (!graph.contains(from) || !graph.contains(to)) {
            return new Result(List.of(), 0.0, 0.0, 0);
        }
        if (AirwayGraph.key(from).equals(AirwayGraph.key(to))) {
            return new Result(List.of(RouteElement.direct(from, from.ident())), 0.0, 0.0, 0);
        }

        double fastestGroundSpeed = trueAirspeedKt + Math.max(0.0, weather.strongestWindKt());
        Map<String, Double> timeToReach = new HashMap<>();
        Map<String, Double> distanceToReach = new HashMap<>();
        Map<String, Arrival> arrivedBy = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<Candidate> frontier =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimate));

        String start = AirwayGraph.key(from);
        String goal = AirwayGraph.key(to);
        timeToReach.put(start, 0.0);
        distanceToReach.put(start, 0.0);
        frontier.add(new Candidate(from, 0.0, 0.0,
                heuristicMinutes(from, to, fastestGroundSpeed)));

        int expanded = 0;
        while (!frontier.isEmpty() && expanded < nodeLimit) {
            Candidate current = frontier.poll();
            String currentKey = AirwayGraph.key(current.fix());
            // A node can be queued several times; the first time it leaves the queue is with
            // its best time, so later copies are stale.
            if (!settled.add(currentKey)) {
                continue;
            }
            expanded++;

            if (currentKey.equals(goal)) {
                return new Result(reconstruct(arrivedBy, from, to), current.distanceNm(),
                        current.minutesSoFar(), expanded);
            }

            for (AirwayGraph.Edge edge : graph.edgesFrom(current.fix())) {
                String neighbourKey = AirwayGraph.key(edge.to());
                if (settled.contains(neighbourKey)) {
                    continue;
                }
                double minutes = current.minutesSoFar()
                        + legMinutes(edge, current.minutesSoFar());
                Double best = timeToReach.get(neighbourKey);
                if (best != null && minutes >= best) {
                    continue;
                }
                timeToReach.put(neighbourKey, minutes);
                distanceToReach.put(neighbourKey, current.distanceNm() + edge.distanceNm());
                arrivedBy.put(neighbourKey, new Arrival(current.fix(), edge.airway()));
                frontier.add(new Candidate(edge.to(), minutes,
                        current.distanceNm() + edge.distanceNm(),
                        minutes + heuristicMinutes(edge.to(), to, fastestGroundSpeed)));
            }
        }
        return new Result(List.of(), 0.0, 0.0, expanded);
    }

    /**
     * Searches between two positions by joining the network at the nearest node to each, as
     * {@link AirwayRouteSearch#searchNear} does. The joining legs are direct and are timed the
     * same way as the rest, so the total is comparable with the network part of the route.
     */
    public Result searchNear(Fix from, Fix to, double joinRadiusNm) {
        Optional<Fix> entry = graph.contains(from)
                ? Optional.of(from)
                : graph.nearestNode(from.position(), joinRadiusNm);
        Optional<Fix> exit = graph.contains(to)
                ? Optional.of(to)
                : graph.nearestNode(to.position(), joinRadiusNm);
        if (entry.isEmpty() || exit.isEmpty()) {
            return new Result(List.of(), 0.0, 0.0, 0);
        }

        Result core = search(entry.get(), exit.get());
        if (!core.found()) {
            return core;
        }

        List<RouteElement> full = new ArrayList<>(core.elements().size() + 2);
        double distance = core.distanceNm();
        double minutes = core.timeMinutes();
        if (!AirwayGraph.key(entry.get()).equals(AirwayGraph.key(from))) {
            full.add(RouteElement.direct(from, from.ident()));
            distance += Geodesy.greatCircleDistanceNm(from.position(), entry.get().position());
            minutes += directMinutes(from.position(), entry.get().position(), 0.0);
        }
        full.addAll(core.elements());
        if (!AirwayGraph.key(exit.get()).equals(AirwayGraph.key(to))) {
            full.add(RouteElement.direct(to, to.ident()));
            distance += Geodesy.greatCircleDistanceNm(exit.get().position(), to.position());
            minutes += directMinutes(exit.get().position(), to.position(), core.timeMinutes());
        }
        return new Result(full, distance, minutes, core.nodesExpanded());
    }

    // ------------------------------------------------------------------ costs

    /** What one airway leg costs in minutes, against the wind at the time it is flown. */
    private double legMinutes(AirwayGraph.Edge edge, double afterMinutes) {
        return directMinutes(edge.from().position(), edge.to().position(), afterMinutes);
    }

    private double directMinutes(GeoPoint from, GeoPoint to, double afterMinutes) {
        double distance = Geodesy.greatCircleDistanceNm(from, to);
        if (distance <= 0.0) {
            return 0.0;
        }
        Instant at = departureTime.plusSeconds(Math.round(afterMinutes * 60.0));
        WindVector wind = weather.windAt(Geodesy.midpoint(from, to), cruiseAltitudeFt, at);
        double course = Geodesy.initialBearingDeg(from, to);
        double groundSpeed = groundSpeed(course, wind);
        // A leg no heading holds is not flyable at this level; costing it as unreachable keeps
        // it out of the answer without the search having to know about wind at all.
        return groundSpeed <= 0.0 ? Double.MAX_VALUE / 4.0 : distance / groundSpeed * 60.0;
    }

    private double groundSpeed(double trueCourseDeg, WindVector wind) {
        try {
            return WindTriangle.groundSpeedKt(trueCourseDeg, trueAirspeedKt, wind);
        } catch (WindTriangle.UnsolvableWindException windTooStrong) {
            return trueAirspeedKt + wind.tailwindComponentKt(trueCourseDeg);
        }
    }

    /**
     * The least time the rest of the route could possibly take: the straight-line distance at
     * the fastest ground speed anywhere in the forecast. Never an overestimate, which is what
     * keeps the search optimal.
     */
    private static double heuristicMinutes(Fix from, Fix to, double fastestGroundSpeedKt) {
        double distance = Geodesy.greatCircleDistanceNm(from.position(), to.position());
        return fastestGroundSpeedKt <= 0.0 ? 0.0 : distance / fastestGroundSpeedKt * 60.0;
    }

    // ------------------------------------------------------------------ reconstruction

    private static List<RouteElement> reconstruct(Map<String, Arrival> arrivedBy, Fix from, Fix to) {
        List<RouteElement> reversed = new ArrayList<>();
        Fix current = to;
        while (!AirwayGraph.key(current).equals(AirwayGraph.key(from))) {
            Arrival arrival = arrivedBy.get(AirwayGraph.key(current));
            if (arrival == null) {
                throw new IllegalStateException(
                        "no path recorded to " + current.ident() + " during reconstruction");
            }
            reversed.add(RouteElement.viaAirway(current, arrival.airway(), false));
            current = arrival.from();
        }
        reversed.add(RouteElement.direct(from, from.ident()));
        Collections.reverse(reversed);
        return markImpliedPoints(reversed);
    }

    /** Marks the points a filed route would not name, as the distance search does. */
    private static List<RouteElement> markImpliedPoints(List<RouteElement> elements) {
        List<RouteElement> marked = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            RouteElement element = elements.get(i);
            boolean sameAirwayContinues = element.viaAirway() != null
                    && i + 1 < elements.size()
                    && element.viaAirway().equals(elements.get(i + 1).viaAirway());
            marked.add(new RouteElement(element.fix(), element.viaAirway(), element.token(),
                    sameAirwayContinues));
        }
        return marked;
    }

    private record Candidate(Fix fix, double minutesSoFar, double distanceNm, double estimate) {
    }

    private record Arrival(Fix from, String airway) {
    }
}
