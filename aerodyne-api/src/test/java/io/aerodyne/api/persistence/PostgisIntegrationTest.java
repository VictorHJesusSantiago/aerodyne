package io.aerodyne.api.persistence;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.Navaid;
import io.aerodyne.navdata.NavaidType;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.WaypointUsage;
import io.aerodyne.navdata.procedure.AltitudeConstraint;
import io.aerodyne.navdata.procedure.LegType;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureLeg;
import io.aerodyne.navdata.procedure.ProcedureTransition;
import io.aerodyne.navdata.procedure.ProcedureType;
import io.aerodyne.navdata.procedure.SpeedConstraint;
import io.aerodyne.navdata.procedure.TransitionKind;
import io.aerodyne.navdata.procedure.TurnDirection;
import io.aerodyne.api.service.NavigationDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The persistence layer against a real PostGIS.
 *
 * <p>This is the test the rest of the suite cannot substitute for. Everything above it runs
 * against an in-memory navigation database and proves the engine is right; none of it proves
 * that the schema Flyway creates is the schema Hibernate expects, that a {@code geography}
 * column round-trips a position with its axes the right way round, or that a spatial query
 * measures in metres rather than degrees. Those only fail against a database.
 *
 * <p>Hibernate is configured to <em>validate</em> rather than generate, so the context starting
 * at all is itself an assertion: every mapped column exists in the migration with a compatible
 * type. A renamed column fails this test before any of its methods run.
 *
 * <p>Needs a PostGIS to talk to, and skips itself cleanly when there is none, so that the build
 * stays green on a machine without one. {@link DatabaseFixture} finds it: a Testcontainers
 * container ordinarily, or an already-running database when {@code AERODYNE_TEST_DB_URL} names
 * one — which is what to use where Testcontainers cannot reach the Docker API.
 *
 * <p>{@link SchemaConsistencyTest} covers the narrower question of whether the entities and the
 * migrations agree, and needs nothing at all to run. It is not a substitute for this — it cannot
 * tell you that a geography column round-trips — but it catches the commonest drift on every
 * build rather than only where Docker is available.
 */
@SpringBootTest
@EnabledIf("databaseIsAvailable")
class PostgisIntegrationTest {

    /**
     * Consulted before anything else runs, and deliberately does not touch the container: asking
     * whether a database is available must not start one.
     */
    static boolean databaseIsAvailable() {
        return DatabaseFixture.isAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        DatabaseFixture.configure(registry);
    }

    @Autowired
    private CycleRepository cycles;
    @Autowired
    private AirportRepository airports;
    @Autowired
    private NavaidRepository navaids;
    @Autowired
    private WaypointRepository waypoints;
    @Autowired
    private AirwayRepository airways;
    @Autowired
    private ProcedureRepository procedures;
    @Autowired
    private CpdlcMessageRepository datalink;
    @Autowired
    private io.aerodyne.api.service.CpdlcService cpdlc;
    @Autowired
    private FlightPlanRepository plans;
    @Autowired
    private NavigationDataService navigation;

    private static final GeoPoint EGLL = GeoPoint.of(51.4775, -0.461389);
    private static final GeoPoint EGKK = GeoPoint.of(51.148056, -0.190278);
    /** Sydney: far away, and in the other hemisphere in both axes. */
    private static final GeoPoint YSSY = GeoPoint.of(-33.946111, 151.177222);

    /** ARINC route type 1 on a departure: the runway transition. */
    private static final char RUNWAY_ROUTE_TYPE = '1';
    /** Route type 2: the common route. */
    private static final char COMMON_ROUTE_TYPE = '2';

    private NavigationEntities.CycleEntity cycle;

    @BeforeEach
    void seed() {
        datalink.deleteAll();
        plans.deleteAll();
        cycles.deleteAll();
        cycle = cycles.save(new NavigationEntities.CycleEntity(
                new AiracCycle(2024, 13), "integration test"));
    }

    @Nested
    @DisplayName("the schema")
    class Schema {

        @Test
        void the_context_starts_which_means_the_entities_match_the_migrations() {
            // Hibernate runs with ddl-auto=validate, so reaching this line is the assertion:
            // every mapped column exists in the schema Flyway built.
            assertThat(cycles.count()).isEqualTo(1);
        }

        @Test
        void the_cycle_row_keeps_its_dates() {
            NavigationEntities.CycleEntity stored =
                    cycles.findByIdentifier("2413").orElseThrow();

            // Cycle 2413 runs from 26 December 2024, a Thursday, for 28 days.
            assertThat(stored.getEffectiveDate()).isEqualTo(LocalDate.of(2024, 12, 26));
            assertThat(stored.getExpiryDate()).isEqualTo(stored.getEffectiveDate().plusDays(28));
            assertThat(stored.getSource()).isEqualTo("integration test");
        }

        @Test
        void finds_the_cycle_in_force_on_a_date() {
            assertThat(cycles.findEffectiveOn(LocalDate.of(2025, 1, 5)))
                    .map(NavigationEntities.CycleEntity::getIdentifier).contains("2413");
            assertThat(cycles.findEffectiveOn(LocalDate.of(2024, 12, 1))).isEmpty();
        }
    }

    @Nested
    @DisplayName("positions")
    class Positions {

        @Test
        void a_position_round_trips_with_its_axes_the_right_way_round() {
            airports.save(NavigationEntities.AirportEntity.from(airport("EGLL", EGLL), cycle));

            Airport read = airports.findByCycleIdAndIcaoCode(cycle.getId(), "EGLL")
                    .orElseThrow().toDomain();

            assertThat(read.position().latitude()).isCloseTo(EGLL.latitude(), within(1e-9));
            assertThat(read.position().longitude()).isCloseTo(EGLL.longitude(), within(1e-9));
        }

        @Test
        void a_southern_and_eastern_position_survives_too() {
            // The axis swap and a sign error look identical near London and different here.
            airports.save(NavigationEntities.AirportEntity.from(airport("YSSY", YSSY), cycle));

            Airport read = airports.findByCycleIdAndIcaoCode(cycle.getId(), "YSSY")
                    .orElseThrow().toDomain();

            assertThat(read.position().latitude()).isCloseTo(-33.946111, within(1e-9));
            assertThat(read.position().longitude()).isCloseTo(151.177222, within(1e-9));
        }
    }

    @Nested
    @DisplayName("spatial queries")
    class Spatial {

        @BeforeEach
        void seedAirports() {
            airports.save(NavigationEntities.AirportEntity.from(airport("EGLL", EGLL), cycle));
            airports.save(NavigationEntities.AirportEntity.from(airport("EGKK", EGKK), cycle));
            airports.save(NavigationEntities.AirportEntity.from(airport("YSSY", YSSY), cycle));
        }

        @Test
        void finds_what_is_near_a_position_nearest_first() {
            List<NavigationEntities.AirportEntity> near = airports.findNear(cycle.getId(),
                    EGLL.latitude(), EGLL.longitude(), nauticalMilesToMetres(50), 10);

            assertThat(near).extracting(NavigationEntities.AirportEntity::getIcaoCode)
                    .containsExactly("EGLL", "EGKK");
        }

        @Test
        void the_radius_is_measured_in_metres_on_the_spheroid_not_in_degrees() {
            // Gatwick is about 24 nm from Heathrow. A query in degrees would let it through a
            // 0.4-unit radius; one in metres will not let it through 20 nm.
            assertThat(airports.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(20), 10))
                    .extracting(NavigationEntities.AirportEntity::getIcaoCode)
                    .containsExactly("EGLL");

            assertThat(airports.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(30), 10))
                    .extracting(NavigationEntities.AirportEntity::getIcaoCode)
                    .containsExactly("EGLL", "EGKK");
        }

        @Test
        void honours_the_result_limit() {
            assertThat(airports.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(100), 1)).hasSize(1);
        }

        @Test
        void a_query_on_the_other_side_of_the_world_finds_only_what_is_there() {
            assertThat(airports.findNear(cycle.getId(), YSSY.latitude(), YSSY.longitude(),
                    nauticalMilesToMetres(100), 10))
                    .extracting(NavigationEntities.AirportEntity::getIcaoCode)
                    .containsExactly("YSSY");
        }

        @Test
        void navaids_and_waypoints_are_searched_the_same_way() {
            navaids.save(NavigationEntities.NavaidEntity.from(new Navaid("OCK", "EG",
                    GeoPoint.of(51.305, -0.4472), NavaidType.VOR_DME, 115_300, -0.3, 276,
                    "OCKHAM"), cycle));
            waypoints.save(NavigationEntities.WaypointEntity.from(
                    Waypoint.enroute("KOLIN", "EG", GeoPoint.of(51.0, -2.0)), cycle));

            assertThat(navaids.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(30), 10)).hasSize(1);
            assertThat(waypoints.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(30), 10)).isEmpty();
            assertThat(waypoints.findNear(cycle.getId(), EGLL.latitude(), EGLL.longitude(),
                    nauticalMilesToMetres(80), 10)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {

        @Test
        void an_airport_keeps_its_runways_and_every_field() {
            airports.save(NavigationEntities.AirportEntity.from(airport("EGLL", EGLL), cycle));

            Airport read = airports.findByCycleIdAndIcaoCode(cycle.getId(), "EGLL")
                    .orElseThrow().toDomain();

            assertThat(read.iataCode()).isEqualTo("LHR");
            assertThat(read.name()).isEqualTo("LONDON HEATHROW");
            assertThat(read.elevationFt()).isEqualTo(83);
            assertThat(read.transitionAltitudeFt()).isEqualTo(6000);
            assertThat(read.longestRunwayFt()).isEqualTo(12_900);
            assertThat(read.runways()).hasSize(2);
            assertThat(read.hasPrecisionApproach()).isTrue();
            assertThat(read.runway("27L").orElseThrow().lengthFt()).isEqualTo(12_799);
            assertThat(read.runway("27L").orElseThrow().gradientPercent())
                    .isCloseTo(-0.001, within(1e-9));
        }

        @Test
        void a_navaid_keeps_its_type_frequency_and_declination() {
            navaids.save(NavigationEntities.NavaidEntity.from(new Navaid("OCK", "EG",
                    GeoPoint.of(51.305, -0.4472), NavaidType.VOR_DME, 115_300, -0.3, 276,
                    "OCKHAM"), cycle));

            Navaid read = navaids.findByCycleIdAndIdent(cycle.getId(), "OCK").get(0).toDomain();

            assertThat(read.type()).isEqualTo(NavaidType.VOR_DME);
            assertThat(read.frequencyKhz()).isEqualTo(115_300);
            assertThat(read.magneticVariationDeg()).isCloseTo(-0.3, within(1e-9));
            assertThat(read.elevationFeet()).hasValue(276.0);
        }

        @Test
        void a_navaid_identifier_may_repeat_within_a_cycle() {
            navaids.save(NavigationEntities.NavaidEntity.from(new Navaid("DME", "EG",
                    EGLL, NavaidType.DME, 112_500, 0, null, "ONE"), cycle));
            navaids.save(NavigationEntities.NavaidEntity.from(new Navaid("DME", "SB",
                    YSSY, NavaidType.DME, 112_500, 0, null, "TWO"), cycle));

            assertThat(navaids.findByCycleIdAndIdent(cycle.getId(), "DME")).hasSize(2);
        }

        @Test
        void a_waypoint_keeps_its_usage_and_terminal_airport() {
            waypoints.save(NavigationEntities.WaypointEntity.from(new Waypoint("DET26", "EG",
                    EGLL, WaypointUsage.TERMINAL, "EGLL", "DETLING"), cycle));

            Waypoint read = waypoints.findByCycleIdAndIdent(cycle.getId(), "DET26").get(0).toDomain();

            assertThat(read.usage()).isEqualTo(WaypointUsage.TERMINAL);
            assertThat(read.terminalAirport()).isEqualTo("EGLL");
        }

        @Test
        void an_airway_keeps_its_points_in_sequence_order() {
            airways.save(NavigationEntities.AirwayEntity.from(airway(), cycle));

            Airway read = airways.findByCycleIdAndIdent(cycle.getId(), "UN862")
                    .orElseThrow().toDomain();

            assertThat(read.points()).extracting(AirwayPoint::fixIdent)
                    .containsExactly("OCK", "KOLIN", "BEDEK");
            assertThat(read.points().get(0).direction()).isEqualTo(DirectionRestriction.FORWARD);
            assertThat(read.points().get(0).level()).isEqualTo(AirwayLevel.HIGH);
            assertThat(read.points().get(0).minimumAltitudeFt()).isEqualTo(24_000);
            assertThat(read.segments()).hasSize(2);
        }

        @Test
        void a_procedure_keeps_its_segments_and_the_legs_in_each() {
            procedures.save(ProcedureEntity.from(departure(), cycle));

            Procedure read = procedures
                    .findByCycleIdAndAirportIcaoAndIdent(cycle.getId(), "EGLL", "KOLI1J")
                    .orElseThrow().toDomain();

            assertThat(read.type()).isEqualTo(ProcedureType.SID);
            assertThat(read.runwayTransitions()).hasSize(1);
            assertThat(read.commonRoute()).isPresent();
            assertThat(read.fixSequence("27L", null)).containsExactly("DET26", "KOLIN");
        }

        @Test
        void a_procedure_leg_keeps_its_constraints_through_the_database() {
            procedures.save(ProcedureEntity.from(departure(), cycle));

            List<ProcedureLeg> legs = procedures
                    .findByCycleIdAndAirportIcaoAndIdent(cycle.getId(), "EGLL", "KOLI1J")
                    .orElseThrow().toDomain().assemble("27L", null);

            // The heading-to-altitude leg has no fix and must not acquire one.
            assertThat(legs.get(0).legType()).isEqualTo(LegType.VA);
            assertThat(legs.get(0).endsAtFix()).isFalse();
            assertThat(legs.get(0).altitude().lowerLimitFt()).hasValue(800);

            assertThat(legs.get(1).isFlyOver()).isTrue();
            assertThat(legs.get(1).speed().speedKt()).isEqualTo(220);
            assertThat(legs.get(2).course()).hasValue(240.0);
            assertThat(legs.get(2).altitude().kind()).isEqualTo(AltitudeConstraint.Kind.BETWEEN);
        }

        @Test
        void an_empty_description_code_comes_back_as_empty_rather_than_null() {
            airways.save(NavigationEntities.AirwayEntity.from(airway(), cycle));

            Airway read = airways.findByCycleIdAndIdent(cycle.getId(), "UN862")
                    .orElseThrow().toDomain();

            assertThat(read.points().get(0).descriptionCode()).isEmpty();
            assertThat(read.points().get(2).endsChain()).isTrue();
        }
    }

    @Nested
    @DisplayName("the datalink archive")
    class Datalink {

        @Test
        void a_clearance_and_its_answer_are_both_kept() {
            io.aerodyne.dispatch.cpdlc.CpdlcMessage clearance = cpdlc.send("GABCD",
                    "EGLL_ATC", "GABCD",
                    io.aerodyne.dispatch.cpdlc.ResponseRequirement.WILCO_UNABLE,
                    List.of(io.aerodyne.dispatch.cpdlc.CpdlcElement.of("UM20", "FL350")),
                    java.time.Instant.parse("2024-06-15T08:00:00Z"));
            cpdlc.reply("GABCD", clearance.messageId(),
                    io.aerodyne.dispatch.cpdlc.CpdlcElement.of("DM0"),
                    java.time.Instant.parse("2024-06-15T08:00:12Z"));

            List<CpdlcMessageEntity> kept =
                    datalink.findBySenderOrRecipientOrderBySentAt("GABCD", "GABCD");

            assertThat(kept).hasSize(2);
            assertThat(kept.get(0).getWireFormat()).contains("UM20:FL350");
            assertThat(kept.get(1).getReferenceId()).isEqualTo(clearance.messageId());
        }

        @Test
        void the_line_that_arrived_is_kept_exactly_as_it_arrived() {
            String wire = "CPDLC/1/7/-/GABCE/EGLL_ATC/2024-06-15T08:00:00Z/WU/DM9:FL390";

            cpdlc.receive("GABCE", wire);

            assertThat(datalink.findBySenderOrRecipientOrderBySentAt("GABCE", "GABCE"))
                    .singleElement()
                    .extracting(CpdlcMessageEntity::getWireFormat)
                    .isEqualTo(wire);
        }

        @Test
        void an_exchange_can_be_read_back_by_the_window_it_happened_in() {
            cpdlc.receive("GABCF",
                    "CPDLC/1/1/-/GABCF/EGLL_ATC/2024-06-15T09:00:00Z/N/DM3");

            assertThat(datalink.findBySentAtBetweenOrderBySentAt(
                    java.time.Instant.parse("2024-06-15T08:00:00Z"),
                    java.time.Instant.parse("2024-06-15T10:00:00Z"))).hasSize(1);
            assertThat(datalink.findBySentAtBetweenOrderBySentAt(
                    java.time.Instant.parse("2024-06-16T00:00:00Z"),
                    java.time.Instant.parse("2024-06-17T00:00:00Z"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("loading a cycle into memory")
    class Loading {

        @Test
        void assembles_the_whole_cycle_into_a_navigation_database() {
            airports.save(NavigationEntities.AirportEntity.from(airport("EGLL", EGLL), cycle));
            navaids.save(NavigationEntities.NavaidEntity.from(new Navaid("OCK", "EG",
                    GeoPoint.of(51.305, -0.4472), NavaidType.VOR_DME, 115_300, -0.3, 276,
                    "OCKHAM"), cycle));
            waypoints.save(NavigationEntities.WaypointEntity.from(
                    Waypoint.enroute("KOLIN", "EG", GeoPoint.of(51.0, -2.0)), cycle));
            airways.save(NavigationEntities.AirwayEntity.from(airway(), cycle));

            NavigationDatabase loaded =
                    navigation.loadCycle("2413").orElseThrow();

            assertThat(loaded.cycle()).contains(new AiracCycle(2024, 13));
            assertThat(loaded.airportCount()).isEqualTo(1);
            assertThat(loaded.airwayCount()).isEqualTo(1);
            // One airport, two runways, one navaid, one waypoint.
            assertThat(loaded.fixCount()).isEqualTo(5);
            assertThat(loaded.airport("EGLL")).isPresent();
            assertThat(loaded.resolve("OCK", EGLL)).isPresent();
        }

        @Test
        void reports_which_cycle_is_in_memory() {
            navigation.loadCycle("2413");

            assertThat(navigation.loadedCycle()).contains("2413");
        }

        @Test
        void a_cycle_that_is_not_stored_loads_nothing() {
            assertThat(navigation.loadCycle("9999")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static Airport airport(String icao, GeoPoint position) {
        Runway rw27l = new Runway(icao, "RW27L", position, 269.5, 12_799, 164, 79, 0, -0.001, "ILL");
        Runway rw09r = new Runway(icao, "RW09R", position, 89.5, 12_799, 164, 77, 306, 0.001, null);
        return new Airport(icao, "LHR", position, 83, 6000, 12_900, "C",
                "LONDON HEATHROW", List.of(rw27l, rw09r));
    }

    /** A departure with a runway segment and a common route, and one leg of each shape. */
    private static Procedure departure() {
        ProcedureLeg va = new ProcedureLeg(10, LegType.VA, null, null,
                TurnDirection.UNSPECIFIED, null, null, null, null, 269.5, null,
                AltitudeConstraint.of("+", 800, null), SpeedConstraint.NONE, "    ", null);
        ProcedureLeg df = new ProcedureLeg(20, LegType.DF, "DET26", "EG",
                TurnDirection.LEFT, null, null, null, null, null, null,
                AltitudeConstraint.of("+", 3000, null), SpeedConstraint.of(" ", 220),
                " Y  ", null);
        ProcedureLeg tf = new ProcedureLeg(10, LegType.TF, "KOLIN", "EG",
                TurnDirection.UNSPECIFIED, null, null, null, null, 240.0, 35.0,
                AltitudeConstraint.of("B", 24000, 10000), SpeedConstraint.NONE, "   Y", -3.0);

        return new Procedure("EGLL", "KOLI1J", ProcedureType.SID, RUNWAY_ROUTE_TYPE, List.of(
                new ProcedureTransition(TransitionKind.RUNWAY, "RW27L", RUNWAY_ROUTE_TYPE,
                        List.of(va, df)),
                new ProcedureTransition(TransitionKind.COMMON, "", COMMON_ROUTE_TYPE,
                        List.of(tf))));
    }

    private static Airway airway() {
        return new Airway("UN862", List.of(
                new AirwayPoint(10, "OCK", "EG", DirectionRestriction.FORWARD,
                        AirwayLevel.HIGH, 24_000, 46_000, ""),
                new AirwayPoint(20, "KOLIN", "EG", DirectionRestriction.FORWARD,
                        AirwayLevel.HIGH, 24_000, 46_000, ""),
                new AirwayPoint(30, "BEDEK", "EG", DirectionRestriction.FORWARD,
                        AirwayLevel.HIGH, 24_000, 46_000, " EE ")));
    }

    private static double nauticalMilesToMetres(double nm) {
        return io.aerodyne.geo.Units.nauticalMilesToMetres(nm);
    }
}
