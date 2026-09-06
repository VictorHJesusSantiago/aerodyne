package io.aerodyne.api.service;

import io.aerodyne.api.persistence.AirportRepository;
import io.aerodyne.api.persistence.AirwayRepository;
import io.aerodyne.api.persistence.CycleRepository;
import io.aerodyne.api.persistence.NavaidRepository;
import io.aerodyne.api.persistence.NavigationEntities;
import io.aerodyne.api.persistence.ProcedureEntity;
import io.aerodyne.api.persistence.ProcedureRepository;
import io.aerodyne.api.persistence.WaypointRepository;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.arinc.Arinc424Diagnostic;
import io.aerodyne.navdata.arinc.Arinc424File;
import io.aerodyne.navdata.arinc.Arinc424Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the navigation database in memory and keeps it in step with the AIRAC cycle in force.
 *
 * <p>The planning engine works against an in-memory {@link NavigationDatabase}, not against the
 * tables. That is not a cache in the usual sense — it is where the data belongs while it is
 * being used. A route search touches tens of thousands of fixes and asks "what is near here"
 * at every expanded node; served from the database that would be tens of thousands of round
 * trips per plan, and the whole cycle is only a few hundred megabytes.
 *
 * <p>The tables are the system of record. They hold every cycle, so a plan can be recomputed
 * against the data it was originally built on, and they are what a new instance loads from at
 * startup. What is in memory is one cycle's worth, swapped when the cycle changes.
 *
 * <p>The reference is swapped atomically, so a plan in flight keeps working against the cycle it
 * started on rather than seeing half of each.
 */
@Service
public class NavigationDataService {

    private static final Logger log = LoggerFactory.getLogger(NavigationDataService.class);

    private final CycleRepository cycles;
    private final AirportRepository airports;
    private final NavaidRepository navaids;
    private final WaypointRepository waypoints;
    private final AirwayRepository airways;
    private final ProcedureRepository procedures;

    private final AtomicReference<NavigationDatabase> current = new AtomicReference<>();
    private final AtomicReference<String> currentCycleIdentifier = new AtomicReference<>();

    public NavigationDataService(CycleRepository cycles, AirportRepository airports,
                                 NavaidRepository navaids, WaypointRepository waypoints,
                                 AirwayRepository airways, ProcedureRepository procedures) {
        this.cycles = cycles;
        this.airports = airports;
        this.navaids = navaids;
        this.waypoints = waypoints;
        this.airways = airways;
        this.procedures = procedures;
    }

    /**
     * The navigation database currently in memory, loading the cycle in force if none has been
     * loaded yet.
     *
     * @throws IllegalStateException when the tables hold no cycle at all, which is a deployment
     *                               problem rather than a request problem
     */
    public NavigationDatabase database() {
        NavigationDatabase loaded = current.get();
        if (loaded != null) {
            return loaded;
        }
        return loadEffective(LocalDate.now(ZoneOffset.UTC)).orElseThrow(() ->
                new IllegalStateException(
                        "no navigation data is loaded; import an AIRAC cycle before planning"));
    }

    /** The identifier of the cycle in memory, when one is. */
    public Optional<String> loadedCycle() {
        return Optional.ofNullable(currentCycleIdentifier.get());
    }

    /** Loads the cycle in force on a date, or the most recent one if none covers it. */
    @Transactional(readOnly = true)
    public Optional<NavigationDatabase> loadEffective(LocalDate date) {
        Optional<NavigationEntities.CycleEntity> cycle = cycles.findEffectiveOn(date);
        if (cycle.isEmpty()) {
            cycle = cycles.findFirstByOrderByEffectiveDateDesc();
            cycle.ifPresent(fallback -> log.warn(
                    "no AIRAC cycle covers {}; falling back to {}, which expired on {}",
                    date, fallback.getIdentifier(), fallback.getExpiryDate()));
        }
        return cycle.map(this::load);
    }

    /** Loads a named cycle, whether or not it is the one in force. */
    @Transactional(readOnly = true)
    public Optional<NavigationDatabase> loadCycle(String identifier) {
        return cycles.findByIdentifier(identifier).map(this::load);
    }

    private NavigationDatabase load(NavigationEntities.CycleEntity cycle) {
        long started = System.nanoTime();
        NavigationDatabase database = NavigationDatabase.builder()
                .cycle(cycle.toDomain())
                .airports(airports.findByCycleId(cycle.getId()).stream()
                        .map(NavigationEntities.AirportEntity::toDomain).toList())
                .navaids(navaids.findByCycleId(cycle.getId()).stream()
                        .map(NavigationEntities.NavaidEntity::toDomain).toList())
                .waypoints(waypoints.findByCycleId(cycle.getId()).stream()
                        .map(NavigationEntities.WaypointEntity::toDomain).toList())
                .airways(airways.findByCycleId(cycle.getId()).stream()
                        .map(NavigationEntities.AirwayEntity::toDomain).toList())
                .procedures(procedures.findByCycleId(cycle.getId()).stream()
                        .map(ProcedureEntity::toDomain).toList())
                .build();

        current.set(database);
        currentCycleIdentifier.set(cycle.getIdentifier());
        log.info("loaded AIRAC {} in {} ms: {} fixes, {} airports, {} airways, {} procedures",
                cycle.getIdentifier(), (System.nanoTime() - started) / 1_000_000,
                database.fixCount(), database.airportCount(), database.airwayCount(),
                database.procedureCount());
        return database;
    }

    /**
     * Imports an ARINC 424 file into a new cycle and makes it current.
     *
     * <p>The import is one transaction: a half-loaded cycle would be worse than no cycle, since
     * a route through the missing half would silently come out shorter than it is.
     */
    @Transactional
    public ImportResult importArinc(Path file, String source) {
        Arinc424File parsed = new Arinc424Parser().parse(file);
        if (parsed.cycle() == null) {
            throw new IllegalArgumentException(
                    "the file carries no readable AIRAC cycle; it cannot be filed against one");
        }
        if (cycles.findByIdentifier(parsed.cycle().identifier()).isPresent()) {
            throw new IllegalStateException(
                    "AIRAC cycle " + parsed.cycle().identifier() + " is already loaded");
        }

        // The column map is taken from ARINC 424-18 and suppliers vary. A layout that does not
        // fit this file would not fail — it would yield fixes in the wrong places — so the rate
        // of dropped records is checked before anything is stored.
        Arinc424Diagnostic.Report layout = Arinc424Diagnostic.check(parsed);
        if (!layout.layoutLooksRight()) {
            throw new IllegalArgumentException(
                    "the ARINC 424 column map does not fit this file: most "
                            + layout.suspectTypes() + " records could not be read. The layout is "
                            + "in Arinc424Layout and may need correcting for this supplier."
                            + System.lineSeparator() + layout.describe());
        }
        log.info("layout check for {}:{}{}", source, System.lineSeparator(), layout.describe());

        NavigationEntities.CycleEntity cycle =
                cycles.save(new NavigationEntities.CycleEntity(parsed.cycle(), source));

        airports.saveAll(parsed.airports().stream()
                .map(airport -> NavigationEntities.AirportEntity.from(airport, cycle)).toList());
        navaids.saveAll(parsed.navaids().stream()
                .map(navaid -> NavigationEntities.NavaidEntity.from(navaid, cycle)).toList());
        waypoints.saveAll(parsed.waypoints().stream()
                .map(waypoint -> NavigationEntities.WaypointEntity.from(waypoint, cycle)).toList());
        airways.saveAll(parsed.airways().stream()
                .map(airway -> NavigationEntities.AirwayEntity.from(airway, cycle)).toList());
        procedures.saveAll(parsed.procedures().stream()
                .map(procedure -> ProcedureEntity.from(procedure, cycle)).toList());

        load(cycle);
        return new ImportResult(parsed.cycle().identifier(), parsed.airports().size(),
                parsed.navaids().size(), parsed.waypoints().size(), parsed.airways().size(),
                parsed.procedures().size(), parsed.issues().size(), parsed.errors().size(),
                layout.describe());
    }

    /** What an import read, what it could not, and whether the column map fitted. */
    public record ImportResult(String cycle, int airports, int navaids, int waypoints,
                               int airways, int procedures, int issues, int errors,
                               String layoutCheck) {
    }
}
