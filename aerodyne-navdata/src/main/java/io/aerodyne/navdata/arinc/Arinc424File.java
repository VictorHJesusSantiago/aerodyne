package io.aerodyne.navdata.arinc;


import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.GridMora;
import io.aerodyne.navdata.MinimumAltitudeGrid;
import io.aerodyne.navdata.Navaid;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.procedure.Procedure;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one ARINC 424 file yielded.
 *
 * <p>Airports arrive with their runways already attached; {@link #runways()} is the same set
 * flattened, which is what the runway index is built from.
 *
 * @param cycle          the AIRAC cycle stamped on the records, when they agree on one
 * @param airports       airport records, each carrying its runways
 * @param runways        every runway record, in file order
 * @param navaids        VHF and NDB navaids
 * @param waypoints      enroute and terminal waypoints
 * @param airways        enroute airways, assembled from their points in sequence order
 * @param procedures     departures, arrivals and approaches, assembled from their legs
 * @param issues         everything that went wrong, in line order
 * @param recordCounts   how many records of each type the file held, including unsupported ones
 */
public record Arinc424File(AiracCycle cycle,
                           List<Airport> airports,
                           List<Runway> runways,
                           List<Navaid> navaids,
                           List<Waypoint> waypoints,
                           List<Airway> airways,
                           List<Procedure> procedures,
                           List<GridMora> gridMora,
                           List<ArincParseIssue> issues,
                           Map<ArincRecordType, Integer> recordCounts) {

    /** A file read before grid MORA was decoded. */
    public Arinc424File(AiracCycle cycle, List<Airport> airports, List<Runway> runways,
                        List<Navaid> navaids, List<Waypoint> waypoints, List<Airway> airways,
                        List<Procedure> procedures, List<ArincParseIssue> issues,
                        Map<ArincRecordType, Integer> recordCounts) {
        this(cycle, airports, runways, navaids, waypoints, airways, procedures, List.of(),
                issues, recordCounts);
    }

    public Arinc424File {
        gridMora = List.copyOf(gridMora);
        airports = List.copyOf(airports);
        runways = List.copyOf(runways);
        navaids = List.copyOf(navaids);
        waypoints = List.copyOf(waypoints);
        airways = List.copyOf(airways);
        procedures = List.copyOf(procedures);
        issues = List.copyOf(issues);
        recordCounts = Map.copyOf(new EnumMap<>(recordCounts));
    }

    public Optional<AiracCycle> airacCycle() {
        return Optional.ofNullable(cycle);
    }

    /** The issues that cost a record, as opposed to those that merely noted something. */
    public List<ArincParseIssue> errors() {
        return issues.stream().filter(ArincParseIssue::isError).toList();
    }

    /** Total navigation objects read, which is what a load log reports. */
    public int objectCount() {
        return airports.size() + runways.size() + navaids.size() + waypoints.size()
                + airways.size() + procedures.size() + gridMora.size();
    }

    /** The grid MORA records, indexed for lookup. */
    public MinimumAltitudeGrid minimumAltitudes() {
        return gridMora.isEmpty() ? MinimumAltitudeGrid.EMPTY : new MinimumAltitudeGrid(gridMora);
    }

    /** The procedures published for one aerodrome. */
    public List<Procedure> proceduresFor(String airportIcao) {
        return procedures.stream()
                .filter(procedure -> procedure.airportIcao().equalsIgnoreCase(airportIcao))
                .toList();
    }

    public int recordCount(ArincRecordType type) {
        return recordCounts.getOrDefault(type, 0);
    }

    @Override
    public String toString() {
        return "Arinc424File[cycle=" + (cycle == null ? "unknown" : cycle)
                + ", airports=" + airports.size()
                + ", runways=" + runways.size()
                + ", navaids=" + navaids.size()
                + ", waypoints=" + waypoints.size()
                + ", airways=" + airways.size()
                + ", procedures=" + procedures.size()
                + ", gridMora=" + gridMora.size()
                + ", issues=" + issues.size() + "]";
    }
}
