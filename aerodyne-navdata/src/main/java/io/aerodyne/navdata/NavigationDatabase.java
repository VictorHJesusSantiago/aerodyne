package io.aerodyne.navdata;

import io.aerodyne.geo.CoordinateFormat;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.arinc.Arinc424File;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * An indexed, immutable navigation database for one AIRAC cycle.
 *
 * <p>Resolving a route element is the operation everything else is built on, and it is not a
 * map lookup. Identifiers repeat: a dozen states publish a {@code DME}, and {@code ALB} is a
 * VOR in New York and a waypoint in Angola. So {@link #resolve(String, GeoPoint)} takes the
 * position the route has reached and returns the nearest candidate — which is what a filed
 * route means by the identifier, and what an FMS does with it.
 */
public final class NavigationDatabase {

    private final AiracCycle cycle;
    private final Map<String, List<Fix>> fixesByIdent;
    private final Map<String, Airport> airportsByIcao;
    private final Map<String, Airway> airwaysByIdent;
    private final Map<String, List<Procedure>> proceduresByAirport;
    private final SpatialGrid<Fix> fixGrid;
    private final SpatialGrid<Airport> airportGrid;
    private final MinimumAltitudeGrid minimumAltitudes;

    private NavigationDatabase(AiracCycle cycle,
                               Map<String, List<Fix>> fixesByIdent,
                               Map<String, Airport> airportsByIcao,
                               Map<String, Airway> airwaysByIdent,
                               Map<String, List<Procedure>> proceduresByAirport,
                               SpatialGrid<Fix> fixGrid,
                               SpatialGrid<Airport> airportGrid,
                               MinimumAltitudeGrid minimumAltitudes) {
        this.minimumAltitudes = minimumAltitudes;
        this.cycle = cycle;
        this.fixesByIdent = fixesByIdent;
        this.airportsByIcao = airportsByIcao;
        this.airwaysByIdent = airwaysByIdent;
        this.proceduresByAirport = proceduresByAirport;
        this.fixGrid = fixGrid;
        this.airportGrid = airportGrid;
    }

    /** Indexes a parsed ARINC 424 file. */
    public static NavigationDatabase from(Arinc424File file) {
        return builder()
                .cycle(file.cycle())
                .airports(file.airports())
                .navaids(file.navaids())
                .waypoints(file.waypoints())
                .airways(file.airways())
                .procedures(file.procedures())
                .minimumAltitudes(file.minimumAltitudes())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<AiracCycle> cycle() {
        return Optional.ofNullable(cycle);
    }

    /**
     * The grid minimum off-route altitudes published in this cycle.
     *
     * <p>Empty for a file that carried none, which most regional extracts do. An empty grid
     * answers "unknown" for every square rather than "zero", so a caller cannot mistake a
     * missing grid for flat terrain.
     */
    public MinimumAltitudeGrid minimumAltitudes() {
        return minimumAltitudes;
    }

    public int fixCount() {
        return fixGrid.size();
    }

    public int airportCount() {
        return airportsByIcao.size();
    }

    public int airwayCount() {
        return airwaysByIdent.size();
    }

    public int procedureCount() {
        return proceduresByAirport.values().stream().mapToInt(List::size).sum();
    }

    /** Every fix published under an identifier, in no particular order. */
    public List<Fix> allNamed(String ident) {
        return fixesByIdent.getOrDefault(normalise(ident), List.of());
    }

    /**
     * The one fix meant by an identifier, given where the route has reached.
     *
     * <p>A coordinate written into the route resolves to itself. A named fix resolves to the
     * candidate nearest {@code near}; with nothing to disambiguate against, an identifier with
     * more than one candidate is ambiguous and resolves to empty rather than to a guess.
     */
    public Optional<Fix> resolve(String ident, GeoPoint near) {
        GeoPoint asCoordinate = CoordinateFormat.parseOrNull(ident);
        if (asCoordinate != null) {
            return Optional.of(new CoordinateFix(ident.trim().toUpperCase(Locale.ROOT), asCoordinate));
        }
        List<Fix> candidates = allNamed(ident);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        if (near == null) {
            return Optional.empty();
        }
        return SpatialGrid.closestTo(near, candidates, Fix::position);
    }

    /** The fix published under an identifier in a particular ICAO region. */
    public Optional<Fix> resolve(String ident, String icaoRegion) {
        if (icaoRegion == null || icaoRegion.isBlank()) {
            return allNamed(ident).size() == 1
                    ? Optional.of(allNamed(ident).get(0))
                    : Optional.empty();
        }
        String region = normalise(icaoRegion);
        return allNamed(ident).stream()
                .filter(f -> region.equalsIgnoreCase(f.icaoRegion()))
                .findFirst();
    }

    public Optional<Airport> airport(String icaoCode) {
        return Optional.ofNullable(airportsByIcao.get(normalise(icaoCode)));
    }

    public Optional<Airway> airway(String ident) {
        return Optional.ofNullable(airwaysByIdent.get(normalise(ident)));
    }

    public Collection<Airport> airports() {
        return airportsByIcao.values();
    }

    public Collection<Airway> airways() {
        return airwaysByIdent.values();
    }

    /** Fixes within a radius of a position, nearest first. */
    public List<Fix> fixesWithin(GeoPoint centre, double radiusNm) {
        return fixGrid.within(centre, radiusNm);
    }

    public List<Fix> fixesWithin(GeoPoint centre, double radiusNm, int limit) {
        return fixGrid.within(centre, radiusNm, limit);
    }

    /**
     * Airports within a radius of a position, nearest first — the first pass of alternate
     * selection, before runway length and weather narrow the list.
     */
    public List<Airport> airportsWithin(GeoPoint centre, double radiusNm) {
        return airportGrid.within(centre, radiusNm);
    }

    public List<Airport> airportsWithin(GeoPoint centre, double radiusNm, int limit) {
        return airportGrid.within(centre, radiusNm, limit);
    }

    /** Every procedure published for an aerodrome. */
    public List<Procedure> procedures(String airportIcao) {
        return proceduresByAirport.getOrDefault(normalise(airportIcao), List.of());
    }

    /** The procedures of one kind published for an aerodrome. */
    public List<Procedure> procedures(String airportIcao, ProcedureType type) {
        return procedures(airportIcao).stream()
                .filter(procedure -> procedure.type() == type)
                .toList();
    }

    /** One named procedure at an aerodrome. */
    public Optional<Procedure> procedure(String airportIcao, String ident) {
        String wanted = ProcedureType.normaliseIdent(ident);
        return procedures(airportIcao).stream()
                .filter(procedure -> ProcedureType.normaliseIdent(procedure.ident()).equals(wanted))
                .findFirst();
    }

    /**
     * The procedures of a kind that serve a runway. A procedure with no runway-specific segment
     * serves every runway, so it is included rather than filtered out.
     */
    public List<Procedure> proceduresForRunway(String airportIcao, ProcedureType type,
                                               String runwayDesignator) {
        return procedures(airportIcao, type).stream()
                .filter(procedure -> procedure.servesRunway(runwayDesignator))
                .toList();
    }

    /** Every airway that passes through a named fix, which is how a route may leave it. */
    public List<Airway> airwaysThrough(String fixIdent) {
        String ident = normalise(fixIdent);
        return airwaysByIdent.values().stream()
                .filter(a -> a.pointNamed(ident).isPresent())
                .toList();
    }

    private static String normalise(String ident) {
        return ident == null ? "" : ident.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * A coordinate written directly into a route string. It is a {@link Fix} so that the rest
     * of the pipeline need not care whether a route element was named or typed.
     */
    public record CoordinateFix(String ident, GeoPoint position) implements Fix {

        @Override
        public String icaoRegion() {
            return null;
        }

        @Override
        public FixKind kind() {
            return FixKind.COORDINATE;
        }
    }

    /** Assembles a database from collections of navigation objects. */
    public static final class Builder {

        private AiracCycle cycle;
        private final List<Airport> airports = new ArrayList<>();
        private final List<Navaid> navaids = new ArrayList<>();
        private final List<Waypoint> waypoints = new ArrayList<>();
        private final List<Airway> airways = new ArrayList<>();
        private final List<Procedure> procedures = new ArrayList<>();
        private MinimumAltitudeGrid minimumAltitudes = MinimumAltitudeGrid.EMPTY;

        public Builder minimumAltitudes(MinimumAltitudeGrid grid) {
            this.minimumAltitudes = grid == null ? MinimumAltitudeGrid.EMPTY : grid;
            return this;
        }

        public Builder cycle(AiracCycle cycle) {
            this.cycle = cycle;
            return this;
        }

        public Builder airports(Collection<Airport> airports) {
            this.airports.addAll(airports);
            return this;
        }

        public Builder navaids(Collection<Navaid> navaids) {
            this.navaids.addAll(navaids);
            return this;
        }

        public Builder waypoints(Collection<Waypoint> waypoints) {
            this.waypoints.addAll(waypoints);
            return this;
        }

        public Builder airways(Collection<Airway> airways) {
            this.airways.addAll(airways);
            return this;
        }

        public Builder procedures(Collection<Procedure> procedures) {
            this.procedures.addAll(procedures);
            return this;
        }

        public NavigationDatabase build() {
            List<Fix> allFixes = new ArrayList<>(
                    airports.size() + navaids.size() + waypoints.size());
            allFixes.addAll(waypoints);
            allFixes.addAll(navaids);
            allFixes.addAll(airports);
            // Runways are indexed by identifier too, so that a route may end on a threshold.
            airports.forEach(a -> allFixes.addAll(a.runways()));

            Map<String, List<Fix>> byIdent = new LinkedHashMap<>();
            for (Fix fix : allFixes) {
                byIdent.computeIfAbsent(normalise(fix.ident()), k -> new ArrayList<>()).add(fix);
            }
            byIdent.replaceAll((ident, list) -> List.copyOf(list));

            Map<String, Airport> byIcao = new LinkedHashMap<>();
            airports.forEach(a -> byIcao.put(normalise(a.icaoCode()), a));

            Map<String, Airway> byAirwayIdent = new LinkedHashMap<>();
            airways.forEach(a -> byAirwayIdent.put(normalise(a.ident()), a));

            Map<String, List<Procedure>> byAirport = new LinkedHashMap<>();
            procedures.forEach(procedure -> byAirport
                    .computeIfAbsent(normalise(procedure.airportIcao()), k -> new ArrayList<>())
                    .add(procedure));
            byAirport.replaceAll((airport, list) -> List.copyOf(list));

            // Deliberately not Map.copyOf: its iteration order is randomised per JVM, and
            // anything downstream that breaks a tie by iteration order — the route search
            // choosing between two equally short airways — would then produce a different
            // plan on each run. A plan has to be reproducible, so insertion order is kept.
            return new NavigationDatabase(
                    cycle,
                    unmodifiable(byIdent),
                    unmodifiable(byIcao),
                    unmodifiable(byAirwayIdent),
                    unmodifiable(byAirport),
                    SpatialGrid.of(allFixes, Fix::position),
                    SpatialGrid.of(airports, Airport::position),
                    minimumAltitudes);
        }

        private static <K, V> Map<K, V> unmodifiable(Map<K, V> map) {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(map));
        }
    }
}
