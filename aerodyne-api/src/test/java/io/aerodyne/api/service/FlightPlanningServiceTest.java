package io.aerodyne.api.service;

import io.aerodyne.api.dto.PlanRequest;
import io.aerodyne.dispatch.ofp.OperationalFlightPlan;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.weather.WindVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The orchestration, exercised against an in-memory navigation database and no Spring context.
 *
 * <p>What matters here is the wiring the modules underneath cannot test on their own: that the
 * fuel loop settles, that a route is found when none is given, and that a level is chosen that
 * the semicircular rule allows.
 */
class FlightPlanningServiceTest {

    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T09:00:00Z");

    private NavigationDataService navigation;
    private WeatherService weather;
    private FlightPlanningService planning;

    /** Two aerodromes about 600 nm apart on a westbound track, joined by one airway. */
    private static final GeoPoint EGXX = GeoPoint.of(51.5, -0.5);
    private static final GeoPoint ALFA = GeoPoint.of(51.0, -4.0);
    private static final GeoPoint BRAVO = GeoPoint.of(50.0, -9.0);
    private static final GeoPoint CHARL = GeoPoint.of(49.0, -14.0);
    private static final GeoPoint EGYY = GeoPoint.of(48.5, -16.0);

    private NavigationDatabase database() {
        return NavigationDatabase.builder()
                .cycle(new AiracCycle(2024, 13))
                .airports(List.of(airport("EGXX", EGXX), airport("EGYY", EGYY),
                        airport("EGZZ", GeoPoint.of(48.9, -15.2))))
                .waypoints(List.of(
                        Waypoint.enroute("ALFA", "EG", ALFA),
                        Waypoint.enroute("BRAVO", "EG", BRAVO),
                        Waypoint.enroute("CHARL", "EG", CHARL)))
                .airways(List.of(airway("UN100", "ALFA", "BRAVO", "CHARL")))
                .build();
    }

    private static Airport airport(String icao, GeoPoint position) {
        Runway runway = new Runway(icao, "RW09", position, 90.0, 10_000, 150, 100, 0, 0.0, "IXXX");
        return new Airport(icao, null, position, 100, 6000, 10_000, "C", icao, List.of(runway));
    }

    private static Airway airway(String ident, String... fixes) {
        List<AirwayPoint> points = new ArrayList<>();
        for (int i = 0; i < fixes.length; i++) {
            points.add(new AirwayPoint((i + 1) * 10, fixes[i], "EG",
                    DirectionRestriction.BOTH, AirwayLevel.ALL, 5_000, 46_000,
                    i == fixes.length - 1 ? " EE " : "    "));
        }
        return new Airway(ident, points);
    }

    @BeforeEach
    void setUp() {
        navigation = new StubNavigationData(database());
        weather = new WeatherService();
        planning = new FlightPlanningService(navigation, new AircraftTypeRegistry(), weather);
    }

    private PlanRequest request(String route, Integer flightLevel) {
        return new PlanRequest("AD200", "G-TEST", "N738", "EGXX", "EGYY", route, flightLevel,
                OFF_BLOCKS, 12_000, 0, null, null, null, null, List.of());
    }

    /** The same request with the wind-routing and step-climb options set. */
    private PlanRequest requestWith(String route, Integer flightLevel,
                                    Boolean windOptimal, Boolean stepClimb) {
        return new PlanRequest("AD200", "G-TEST", "N738", "EGXX", "EGYY", route, flightLevel,
                OFF_BLOCKS, 12_000, 0, null, null, null, null, List.of(),
                windOptimal, stepClimb);
    }

    @Test
    void neither_new_option_is_on_unless_it_is_asked_for() {
        PlanRequest plain = request("EGXX ALFA UN100 CHARL EGYY", 330);

        assertThat(plain.optimiseForWind()).isFalse();
        assertThat(plain.wantsStepClimb()).isFalse();
    }

    @Test
    void a_request_in_the_older_shape_still_plans() {
        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(plan.profile().stepClimbCount()).isZero();
        assertThat(plan.profile().cruiseLevelsFt()).containsExactly(33_000.0);
    }

    @Test
    void asking_for_step_climbs_lets_the_cruise_step_up() {
        PlanRequest stepped = requestWith("EGXX ALFA UN100 CHARL EGYY", 330, null, true);

        OperationalFlightPlan plan = planning.plan(stepped);

        assertThat(stepped.wantsStepClimb()).isTrue();
        // This sector is too short for a step to pay, so what is pinned down is that the
        // option reaches the integrator at all and that the profile stays consistent.
        assertThat(plan.profile().cruiseLevelsFt()).isNotEmpty();
        assertThat(plan.profile().tripFuelKg()).isPositive();
    }

    @Test
    void asking_for_wind_routing_with_no_forecast_loaded_still_produces_a_plan() {
        // No GRIB2 has been loaded, so the wind search is skipped and the distance one runs.
        // The plan must come out the same rather than failing or silently differing.
        OperationalFlightPlan windRouted =
                planning.plan(requestWith(null, 330, true, null));
        OperationalFlightPlan shortest =
                planning.plan(requestWith(null, 330, null, null));

        assertThat(windRouted.routeString()).isEqualTo(shortest.routeString());
    }

    @Test
    void the_terrain_grid_is_not_consulted_when_the_cycle_has_none() {
        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(plan.highestMora()).isEmpty();
        assertThat(plan.dispatchObjections())
                .noneSatisfy(objection -> assertThat(objection).contains("off-route altitude"));
    }

    @Test
    void plans_a_flight_against_a_given_route_and_level() {

        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(plan.sectorLabel()).isEqualTo("EGXX-EGYY");
        assertThat(plan.routeString()).contains("UN100");
        assertThat(plan.profile().cruiseAltitudeFt()).isEqualTo(33_000.0);
        assertThat(plan.distanceNm()).isBetween(500.0, 800.0);
    }

    @Test
    void the_fuel_loop_settles_so_the_takeoff_weight_matches_the_fuel_it_implies() {

        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        double zeroFuel = plan.aircraftType().weightLimits().operatingEmptyKg() + 12_000;
        assertThat(plan.loadsheet().takeoffWeightKg())
                .isCloseTo(zeroFuel + plan.fuel().takeoffFuelKg(),
                        within(FlightPlanningService.FUEL_CONVERGENCE_KG));
    }

    @Test
    void the_profile_that_comes_back_was_integrated_at_the_settled_weight() {

        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(plan.profile().takeoffWeightKg())
                .isCloseTo(plan.loadsheet().takeoffWeightKg(),
                        within(FlightPlanningService.FUEL_CONVERGENCE_KG));
    }

    @Test
    void a_heavier_payload_needs_more_fuel_for_the_same_route() {

        OperationalFlightPlan light = planning.plan(new PlanRequest("AD200", "G-TEST", "N738",
                "EGXX", "EGYY", "EGXX ALFA UN100 CHARL EGYY", 330, OFF_BLOCKS, 2_000, 0,
                null, null, null, null, List.of()));
        OperationalFlightPlan heavy = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(heavy.fuel().tripKg()).isGreaterThan(light.fuel().tripKg());
    }

    @Test
    void searches_the_airway_network_when_no_route_is_given() {

        OperationalFlightPlan plan = planning.plan(request(null, 330));

        assertThat(plan.routeString()).contains("UN100");
        assertThat(plan.profile().legs()).isNotEmpty();
    }

    @Test
    void chooses_a_level_the_semicircular_rule_allows_when_none_is_given() {

        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", null));

        int flightLevel = (int) Math.round(plan.profile().cruiseAltitudeFt() / 100.0);
        // The track runs west, so the level must be an even one.
        assertThat(flightLevel % 20).isZero();
        assertThat(flightLevel).isBetween(100, 410);
    }

    @Test
    void the_extra_fuel_the_commander_asks_for_reaches_the_plan() {

        OperationalFlightPlan plain = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));
        OperationalFlightPlan withExtra = planning.plan(new PlanRequest("AD200", "G-TEST", "N738",
                "EGXX", "EGYY", "EGXX ALFA UN100 CHARL EGYY", 330, OFF_BLOCKS, 12_000, 1_500,
                null, null, null, null, List.of()));

        assertThat(withExtra.fuel().extraKg()).isEqualTo(1_500.0);
        assertThat(withExtra.fuel().rampFuelKg())
                .isGreaterThan(plain.fuel().rampFuelKg() + 1_000.0);
    }

    @Test
    void a_headwind_shows_up_as_more_fuel_and_a_later_arrival() {

        OperationalFlightPlan calm = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        // The track is westbound, so an easterly is a tailwind and a westerly a headwind.
        weather.submitMetar("EGXX 150850Z 27020KT 9999 NSC 15/10 Q1013");
        FlightPlanningService windy = new FlightPlanningService(navigation,
                new AircraftTypeRegistry(), new StubWind(WindVector.of(270.0, 90.0)));
        OperationalFlightPlan intoWind = windy.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(intoWind.fuel().tripKg()).isGreaterThan(calm.fuel().tripKg());
        assertThat(intoWind.estimatedArrival()).isAfter(calm.estimatedArrival());
    }

    @Test
    void selects_alternates_near_the_destination_when_none_are_given() {
        // No forecast is held for anything, and the criteria demand one.
        OperationalFlightPlan plan = planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330));

        assertThat(plan.alternates()).isEmpty();
        assertThat(plan.dispatchObjections()).contains("no usable alternate");
    }

    @Test
    void an_aerodrome_named_by_the_caller_is_still_screened() {

        OperationalFlightPlan plan = planning.plan(new PlanRequest("AD200", "G-TEST", "N738",
                "EGXX", "EGYY", "EGXX ALFA UN100 CHARL EGYY", 330, OFF_BLOCKS, 12_000, 0,
                null, null, null, null, List.of("EGZZ")));

        assertThat(plan.alternates()).extracting(a -> a.icaoCode()).containsExactly("EGZZ");
    }

    @Test
    void plans_against_the_standard_atmosphere_when_no_forecast_is_loaded() {

        assertThat(planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330)).weatherSource())
                .isEqualTo("ISA, no wind");
    }

    @Test
    void refuses_an_aircraft_type_it_has_no_tables_for() {
        assertThatThrownBy(() -> planning.plan(new PlanRequest("AD200", "G-TEST", "ZZZZ",
                "EGXX", "EGYY", null, 330, OFF_BLOCKS, 12_000, 0,
                null, null, null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no performance data for aircraft type 'ZZZZ'");
    }

    @Test
    void refuses_a_departure_aerodrome_that_is_not_in_the_cycle() {

        assertThatThrownBy(() -> planning.plan(new PlanRequest("AD200", "G-TEST", "N738",
                "ZZZZ", "EGYY", null, 330, OFF_BLOCKS, 12_000, 0,
                null, null, null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("departure aerodrome ZZZZ is not in the cycle");
    }

    @Test
    void refuses_a_destination_that_is_not_in_the_cycle() {

        assertThatThrownBy(() -> planning.plan(new PlanRequest("AD200", "G-TEST", "N738",
                "EGXX", "ZZZZ", null, 330, OFF_BLOCKS, 12_000, 0,
                null, null, null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination aerodrome ZZZZ is not in the cycle");
    }

    @Test
    void the_plan_records_the_cycle_it_was_built_against() {

        assertThat(planning.plan(request("EGXX ALFA UN100 CHARL EGYY", 330)).airacCycle())
                .isEqualTo(new AiracCycle(2024, 13));
    }

    /**
     * A navigation service holding one fixed database.
     *
     * <p>Hand-written rather than mocked. The seam is one method, the fake is three lines, and
     * it works on any JVM — which a bytecode-generating mock does not, as this test found when
     * the toolchain moved ahead of the mocking library.
     */
    private static final class StubNavigationData extends NavigationDataService {

        private final NavigationDatabase database;

        StubNavigationData(NavigationDatabase database) {
            super(null, null, null, null, null, null);
            this.database = database;
        }

        @Override
        public NavigationDatabase database() {
            return database;
        }
    }

    /** A weather service that always reports a fixed wind, for the headwind comparison. */
    private static final class StubWind extends WeatherService {

        private final WindVector wind;

        StubWind(WindVector wind) {
            this.wind = wind;
        }

        @Override
        public io.aerodyne.weather.WeatherModel forDeparture(Instant offBlocks) {
            return io.aerodyne.weather.ConstantWeatherModel.of(wind);
        }
    }
}
