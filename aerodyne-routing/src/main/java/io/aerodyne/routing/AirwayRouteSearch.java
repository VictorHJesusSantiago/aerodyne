package io.aerodyne.routing;

import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds the shortest route through the airway network between two fixes.
 *
 * <p>A* over {@link AirwayGraph}, with the great-circle distance to the destination as the
 * heuristic. That heuristic is <em>admissible</em> — it never overestimates, because no route
 * through a network of great-circle legs can be shorter than the direct great circle — which is
 * what makes the first route the search completes provably the shortest one, rather than merely
 * a good one.
 *
 * <p>What the search optimises is distance, not time. A shortest route is not always the
 * quickest one: a longer route down a jet stream arrives sooner. {@link WindOptimalRouteSearch}
 * is the same search with time-to-fly as the edge cost, and is the one to use when a forecast is
 * loaded. This one is what remains correct when none is, and it is what a distance-based check —
 * how far a plan strays from the shortest routing — is measured against.
 */
public final class AirwayRouteSearch {

    /** How many nodes the search will expand before giving up. */
    private static final int DEFAULT_NODE_LIMIT = 200_000;

    private final AirwayGraph graph;
    private final int nodeLimit;

    public AirwayRouteSearch(AirwayGraph graph) {
        this(graph, DEFAULT_NODE_LIMIT);
    }

    public AirwayRouteSearch(AirwayGraph graph, int nodeLimit) {
        this.graph = graph;
        this.nodeLimit = nodeLimit;
    }

    /**
     * The result of a search.
     *
     * @param elements       the route, starting with the origin fix
     * @param distanceNm     its total length
     * @param nodesExpanded  how much work the search did, for diagnostics
     */
    public record Result(List<RouteElement> elements, double distanceNm, int nodesExpanded) {

        public Result {
            elements = List.copyOf(elements);
        }

        public boolean found() {
            return !elements.isEmpty();
        }

        /** The airways the route uses, in order, without repeats. */
        public List<String> airways() {
            return elements.stream()
                    .map(RouteElement::viaAirway)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }

        /**
         * The route as a filed route string: the origin, then each airway and the fix it is
         * left at.
         */
        public String toRouteString() {
            return new ExpandedRoute("", elements, List.of()).toRouteString();
        }
    }

    /** Searches from one fix to another. Returns a result with no elements when none exists. */
    public Result search(Fix from, Fix to) {
        if (!graph.contains(from) || !graph.contains(to)) {
            return new Result(List.of(), 0.0, 0);
        }
        if (AirwayGraph.key(from).equals(AirwayGraph.key(to))) {
            return new Result(List.of(RouteElement.direct(from, from.ident())), 0.0, 0);
        }

        Map<String, Double> costToReach = new HashMap<>();
        Map<String, Arrival> arrivedBy = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<Candidate> frontier =
                new PriorityQueue<>(java.util.Comparator.comparingDouble(Candidate::estimate));

        String start = AirwayGraph.key(from);
        String goal = AirwayGraph.key(to);
        costToReach.put(start, 0.0);
        frontier.add(new Candidate(from, 0.0, heuristic(from, to)));

        int expanded = 0;
        while (!frontier.isEmpty() && expanded < nodeLimit) {
            Candidate current = frontier.poll();
            String currentKey = AirwayGraph.key(current.fix());
            // A node can be queued several times with different estimates; the first time it
            // comes off the queue is with its best cost, so later copies are stale.
            if (!settled.add(currentKey)) {
                continue;
            }
            expanded++;

            if (currentKey.equals(goal)) {
                return new Result(reconstruct(arrivedBy, from, to), current.costSoFar(), expanded);
            }

            for (AirwayGraph.Edge edge : graph.edgesFrom(current.fix())) {
                String neighbourKey = AirwayGraph.key(edge.to());
                if (settled.contains(neighbourKey)) {
                    continue;
                }
                double cost = current.costSoFar() + edge.distanceNm();
                Double best = costToReach.get(neighbourKey);
                if (best != null && cost >= best) {
                    continue;
                }
                costToReach.put(neighbourKey, cost);
                arrivedBy.put(neighbourKey, new Arrival(current.fix(), edge.airway()));
                frontier.add(new Candidate(edge.to(), cost, cost + heuristic(edge.to(), to)));
            }
        }
        return new Result(List.of(), 0.0, expanded);
    }

    /**
     * Searches between two positions by joining the network at the nearest node to each. The
     * legs from the origin to that node and from the last node to the destination are direct,
     * which is what a departure and arrival procedure would otherwise provide.
     */
    public Result searchNear(Fix from, Fix to, double joinRadiusNm) {
        Optional<Fix> entry = graph.contains(from)
                ? Optional.of(from)
                : graph.nearestNode(from.position(), joinRadiusNm);
        Optional<Fix> exit = graph.contains(to)
                ? Optional.of(to)
                : graph.nearestNode(to.position(), joinRadiusNm);
        if (entry.isEmpty() || exit.isEmpty()) {
            return new Result(List.of(), 0.0, 0);
        }

        Result core = search(entry.get(), exit.get());
        if (!core.found()) {
            return core;
        }

        List<RouteElement> full = new ArrayList<>(core.elements().size() + 2);
        double distance = core.distanceNm();
        if (!AirwayGraph.key(entry.get()).equals(AirwayGraph.key(from))) {
            full.add(RouteElement.direct(from, from.ident()));
            distance += Geodesy.greatCircleDistanceNm(from.position(), entry.get().position());
        }
        full.addAll(core.elements());
        if (!AirwayGraph.key(exit.get()).equals(AirwayGraph.key(to))) {
            full.add(RouteElement.direct(to, to.ident()));
            distance += Geodesy.greatCircleDistanceNm(exit.get().position(), to.position());
        }
        return new Result(full, distance, core.nodesExpanded());
    }

    /**
     * The straight-line distance to the goal. Never longer than any real route to it, which is
     * what keeps the search optimal.
     */
    private static double heuristic(Fix from, Fix to) {
        return Geodesy.greatCircleDistanceNm(from.position(), to.position());
    }

    private static List<RouteElement> reconstruct(Map<String, Arrival> arrivedBy, Fix from, Fix to) {
        List<RouteElement> reversed = new ArrayList<>();
        Fix current = to;
        while (!AirwayGraph.key(current).equals(AirwayGraph.key(from))) {
            Arrival arrival = arrivedBy.get(AirwayGraph.key(current));
            if (arrival == null) {
                // Only reachable if the predecessor map is inconsistent with the goal test.
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

    /**
     * Marks the points that a filed route would not name: those in the middle of a run along one
     * airway, where the flight plan writes only the airway and the fix it is left at.
     */
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

    private record Candidate(Fix fix, double costSoFar, double estimate) {
    }

    private record Arrival(Fix from, String airway) {
    }
}
