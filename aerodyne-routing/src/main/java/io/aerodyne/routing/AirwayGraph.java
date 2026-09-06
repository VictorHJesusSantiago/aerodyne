package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwaySegment;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.NavigationDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The airway network as a directed graph, built for one cruise altitude.
 *
 * <p>Altitude is baked in at construction rather than checked during the search, and that is the
 * point of the class. An airway's usability depends on the level being flown — a low-level
 * airway is not available at FL350, and a one-way airway is not available against its direction
 * — so a graph built for FL350 contains only the legs a flight at FL350 may actually use, and
 * anything the search finds in it is by construction filable.
 *
 * <p>Nodes are fixes, keyed by identifier and ICAO region, because identifiers repeat. Edges are
 * directed: a two-way airway contributes an edge each way, a one-way airway only one.
 *
 * <p>Immutable once built, and building it is the expensive part — a full cycle takes a second
 * or so — so a caller planning several flights at the same level should build once and reuse.
 */
public final class AirwayGraph {

    private static final Logger log = LoggerFactory.getLogger(AirwayGraph.class);

    private final double altitudeFt;
    private final Map<String, Fix> nodes;
    private final Map<String, List<Edge>> edges;

    private AirwayGraph(double altitudeFt, Map<String, Fix> nodes, Map<String, List<Edge>> edges) {
        this.altitudeFt = altitudeFt;
        this.nodes = nodes;
        this.edges = edges;
    }

    /** One usable leg out of a fix. */
    public record Edge(Fix from, Fix to, String airway, double distanceNm) {
    }

    /**
     * Builds the graph of everything flyable at an altitude.
     *
     * <p>An airway leg whose fixes cannot be resolved is skipped: a regional navdata extract
     * routinely names fixes it does not itself contain, and a leg with no position is not a leg.
     */
    public static AirwayGraph build(NavigationDatabase database, double altitudeFt) {
        Map<String, Fix> nodes = new LinkedHashMap<>();
        // Insertion-ordered so that the edges out of a fix are always offered to the search in
        // the same order, and two equally short routes resolve to the same one every run.
        Map<String, List<Edge>> edges = new LinkedHashMap<>();
        int unresolved = 0;

        for (Airway airway : database.airways()) {
            for (AirwaySegment segment : airway.segments()) {
                if (!segment.usableAt(altitudeFt)) {
                    continue;
                }
                Optional<Fix> from = resolve(database, segment.from().fixIdent(),
                        segment.from().fixRegion());
                Optional<Fix> to = resolve(database, segment.to().fixIdent(),
                        segment.to().fixRegion());
                if (from.isEmpty() || to.isEmpty()) {
                    unresolved++;
                    continue;
                }
                double distance = Geodesy.greatCircleDistanceNm(
                        from.get().position(), to.get().position());

                nodes.putIfAbsent(key(from.get()), from.get());
                nodes.putIfAbsent(key(to.get()), to.get());
                if (segment.direction().allowsForward()) {
                    addEdge(edges, from.get(), to.get(), airway.ident(), distance);
                }
                if (segment.direction().allowsBackward()) {
                    addEdge(edges, to.get(), from.get(), airway.ident(), distance);
                }
            }
        }
        if (unresolved > 0) {
            log.debug("skipped {} airway legs whose fixes are not in this navdata extract", unresolved);
        }
        edges.replaceAll((node, list) -> List.copyOf(list));
        return new AirwayGraph(altitudeFt,
                java.util.Collections.unmodifiableMap(nodes),
                java.util.Collections.unmodifiableMap(edges));
    }

    public double altitudeFt() {
        return altitudeFt;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.values().stream().mapToInt(List::size).sum();
    }

    /** The legs leaving a fix. */
    public List<Edge> edgesFrom(Fix fix) {
        return edges.getOrDefault(key(fix), List.of());
    }

    /** True when the fix is on the network at this altitude at all. */
    public boolean contains(Fix fix) {
        return nodes.containsKey(key(fix));
    }

    public Collection<Fix> nodes() {
        return nodes.values();
    }

    /**
     * The node nearest a position, which is how a departure or destination joins the network.
     * An aerodrome is rarely on an airway itself, so the route has to start from the closest
     * fix that is.
     */
    public Optional<Fix> nearestNode(GeoPoint position, double maxDistanceNm) {
        Fix nearest = null;
        double best = maxDistanceNm;
        for (Fix candidate : nodes.values()) {
            double distance = Geodesy.greatCircleDistanceNm(position, candidate.position());
            if (distance <= best) {
                best = distance;
                nearest = candidate;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static Optional<Fix> resolve(NavigationDatabase database, String ident, String region) {
        Optional<Fix> byRegion = database.resolve(ident, region);
        if (byRegion.isPresent()) {
            return byRegion;
        }
        List<Fix> candidates = database.allNamed(ident);
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private static void addEdge(Map<String, List<Edge>> edges, Fix from, Fix to,
                                String airway, double distanceNm) {
        edges.computeIfAbsent(key(from), k -> new ArrayList<>())
                .add(new Edge(from, to, airway, distanceNm));
    }

    /** Identifier and region together, since neither alone identifies a fix. */
    static String key(Fix fix) {
        String region = fix.icaoRegion() == null ? "" : fix.icaoRegion();
        return fix.ident().toUpperCase(Locale.ROOT) + "|" + region.toUpperCase(Locale.ROOT);
    }
}
