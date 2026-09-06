package io.aerodyne.dispatch.ofp;

import io.aerodyne.dispatch.AlternateCandidate;
import io.aerodyne.dispatch.DiversionCalculator;
import io.aerodyne.dispatch.EtopsAnalyser;
import io.aerodyne.dispatch.EtopsAnalysis;
import io.aerodyne.dispatch.RoutePath;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Runway;
import io.aerodyne.performance.AircraftType;
import io.aerodyne.performance.AircraftTypes;
import io.aerodyne.performance.DataProvenance;
import io.aerodyne.performance.FlightProfile;
import io.aerodyne.performance.FlightProfileCalculator;
import io.aerodyne.performance.FuelBreakdown;
import io.aerodyne.performance.FuelPolicy;
import io.aerodyne.performance.Loadsheet;
import io.aerodyne.performance.WeightAndBalance;
import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindVector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole engine end to end: a route becomes an integrated profile, a fuel plan, a loadsheet
 * and an ETOPS assessment, and those become the document a crew is dispatched with.
 */
class OperationalFlightPlanTest {

    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T09:00:00Z");
    /**
     * The worked example, re-marked as manufacturer data.
     *
     * <p>The example figures are {@link DataProvenance#REPRESENTATIVE}, which by design makes
     * every plan built on them undispatchable. That is the right behaviour and it is asserted
     * below; but a test of the <em>other</em> dispatch rules needs a type that does not trip
     * this one, or every such test would pass for the wrong reason.
     */
    private static final AircraftType TYPE = asManufacturerData(AircraftTypes.widebodyTwin());

    private static AircraftType asManufacturerData(AircraftType type) {
        return new AircraftType(type.typeCode(), type.name(), type.wakeCategory(),
                type.engineCount(), type.weightLimits(), type.cruise(), type.climb(),
                type.descent(), type.cgEnvelope(), type.macLengthMetres(),
                type.macLeadingEdgeArmMetres(), type.operatingEmptyArmMetres(),
                type.diversionSpeedKt(), type.diversionFuelFlowKgPerHour(),
                type.singleEngineCeilingFt(), type.etopsApprovalMinutes(),
                DataProvenance.MANUFACTURER);
    }

    private static final Airport EIDW = airport("EIDW", "DUBLIN", 53.4213, -6.2701, 8_652);
    private static final Airport KBOS = airport("KBOS", "BOSTON LOGAN", 42.3630, -71.0064, 10_083);
    private static final Airport EINN = airport("EINN", "SHANNON", 52.7020, -8.9248, 10_495);
    private static final Airport BIKF = airport("BIKF", "KEFLAVIK", 63.9850, -22.6056, 10_056);
    private static final Airport CYQX = airport("CYQX", "GANDER", 48.9369, -54.5681, 10_500);

    private static Airport airport(String icao, String name, double latitude, double longitude,
                                   int runwayFt) {
        Runway runway = new Runway(icao, "RW10", GeoPoint.of(latitude, longitude), 100.0,
                runwayFt, 200, 100, 0, 0.0, "I" + icao.substring(1));
        return new Airport(icao, null, GeoPoint.of(latitude, longitude), 200, 6000,
                runwayFt, "C", name, List.of(runway));
    }

    /** Dublin to Boston, routed through the usual oceanic entry and exit points. */
    private static final List<GeoPoint> PATH = List.of(
            EIDW.position(),
            GeoPoint.of(53.0, -15.0),
            GeoPoint.of(55.0, -30.0),
            GeoPoint.of(53.0, -45.0),
            GeoPoint.of(47.0, -55.0),
            KBOS.position());

    private OperationalFlightPlan buildPlan(WeatherModel weather, int etopsApprovalMinutes,
                                            double payloadKg) {
        FlightProfileCalculator calculator = new FlightProfileCalculator(TYPE, weather);
        FlightProfile profile = calculator.compute(new FlightProfileCalculator.Request(
                PATH, 210_000, 35_000, EIDW.elevationFt(), KBOS.elevationFt(), OFF_BLOCKS));

        FuelPolicy policy = FuelPolicy.standard(3_600.0, 600.0);
        FuelBreakdown fuel = policy.apply(profile.tripFuelKg(), 4_200.0, 0.0, 0.0);

        Loadsheet loadsheet = WeightAndBalance.forType(TYPE)
                .load("Passengers", payloadKg * 0.75, 28.0)
                .load("Cargo", payloadKg * 0.25, 29.5)
                .fuel(fuel.takeoffFuelKg(), 28.6)
                .tripFuel(profile.tripFuelKg())
                .compute();

        RoutePath path = new RoutePath(PATH);
        EtopsAnalysis etops = new EtopsAnalyser(
                new DiversionCalculator(weather, TYPE.diversionSpeedKt(), 22_000.0))
                // Screened against their forecasts: a plan that had not screened them is
                // objected to, and this fixture is the plan that is within every limit.
                .analyse(path, List.of(EINN, BIKF, CYQX), etopsApprovalMinutes, OFF_BLOCKS,
                        profile.averageGroundSpeedKt(), true);

        return new OperationalFlightPlan("AD101", "EI-ABC", TYPE, EIDW, KBOS,
                List.of(new AlternateCandidate(CYQX, 420.0, 20.0, null, List.of())),
                "EIDW DCT 5300N01500W 5500N03000W 5300N04500W 4700N05500W DCT KBOS",
                profile, fuel, loadsheet, etops,
                new AiracCycle(2024, 13), weather.source(), OFF_BLOCKS,
                Instant.parse("2024-06-15T07:30:00Z"));
    }

    @Test
    void a_transatlantic_sector_produces_a_plausible_plan() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 180, 30_000);

        assertThat(plan.distanceNm()).isBetween(2_500.0, 3_000.0);
        assertThat(plan.profile().tripTimeMinutes()).isBetween(330.0, 420.0);
        assertThat(plan.fuel().tripKg()).isBetween(30_000.0, 50_000.0);
        assertThat(plan.sectorLabel()).isEqualTo("EIDW-KBOS");
    }

    @Test
    void the_estimated_arrival_follows_from_the_integrated_profile() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 180, 30_000);

        assertThat(plan.estimatedArrival()).isEqualTo(plan.profile().arrivesAt());
        assertThat(plan.estimatedArrival()).isAfter(OFF_BLOCKS);
    }

    @Test
    void a_plan_within_every_limit_is_released() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 180, 30_000);

        assertThat(plan.dispatchObjections()).isEmpty();
        assertThat(plan.isDispatchable()).isTrue();
    }

    @Test
    void representative_performance_figures_stop_the_dispatch() {
        // The same plan, built on the example tables as they ship.
        OperationalFlightPlan onExampleData = new OperationalFlightPlan(
                "AD101", "EI-ABC", AircraftTypes.widebodyTwin(), EIDW, KBOS,
                List.of(new AlternateCandidate(CYQX, 420.0, 20.0, null, List.of())),
                "EIDW DCT KBOS",
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000).profile(),
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000).fuel(),
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000).loadsheet(),
                null, new AiracCycle(2024, 13), "ISA, no wind", OFF_BLOCKS,
                Instant.parse("2024-06-15T07:30:00Z"));

        assertThat(onExampleData.isDispatchable()).isFalse();
        assertThat(onExampleData.dispatchObjections())
                .anyMatch(objection -> objection.contains("representative rather than "
                        + "manufacturer data"));
    }

    @Test
    void an_ETOPS_approval_too_short_for_the_route_stops_the_dispatch() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 90, 30_000);

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.dispatchObjections())
                .anyMatch(objection -> objection.contains("ETOPS approval"));
    }

    @Test
    void an_overweight_loading_stops_the_dispatch() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 180, 60_000);

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.dispatchObjections())
                .anyMatch(objection -> objection.contains("weight"));
    }

    @Test
    void a_headwind_costs_fuel_and_time_over_the_same_route() {
        OperationalFlightPlan calm = buildPlan(ConstantWeatherModel.isa(), 180, 30_000);
        OperationalFlightPlan westerly = buildPlan(
                ConstantWeatherModel.of(WindVector.of(270.0, 80.0)), 180, 30_000);

        assertThat(westerly.fuel().tripKg()).isGreaterThan(calm.fuel().tripKg());
        assertThat(westerly.estimatedArrival()).isAfter(calm.estimatedArrival());
    }

    @Test
    void the_route_leaves_and_re_enters_the_sixty_minute_area() {
        EtopsAnalysis etops = buildPlan(ConstantWeatherModel.isa(), 180, 30_000)
                .etopsAssessment().orElseThrow();

        assertThat(etops.isWithinThreshold()).isFalse();
        assertThat(etops.entryPoint()).isPresent();
        assertThat(etops.exitPoint()).isPresent();
        assertThat(etops.equalTimePoints()).isNotEmpty();
    }

    @Test
    void the_rendered_OFP_carries_every_section_a_crew_needs() {
        String document = new OfpRenderer().render(
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000));

        assertThat(document)
                .contains("OPERATIONAL FLIGHT PLAN")
                .contains("AD101").contains("EI-ABC").contains("EIDW-KBOS")
                .contains("ROUTE")
                .contains("FUEL").contains("TRIP").contains("FINRES").contains("RAMP")
                .contains("WEIGHTS AND BALANCE").contains("% MAC")
                .contains("NAVIGATION LOG").contains("POSITION")
                .contains("ETOPS").contains("EQUAL TIME POINTS")
                .contains("ALTERNATES").contains("CYQX")
                .contains("DISPATCH RELEASE");
    }

    @Test
    void the_navigation_log_has_a_line_for_every_leg() {
        OperationalFlightPlan plan = buildPlan(ConstantWeatherModel.isa(), 180, 30_000);

        String document = new OfpRenderer().render(plan);
        long legLines = document.lines()
                .filter(line -> line.contains("CRZ") || line.contains("CLB") || line.contains("DES"))
                .count();

        assertThat(legLines).isEqualTo(plan.profile().legs().size());
    }

    @Test
    void every_line_of_the_document_fits_a_fixed_width_page() {
        String document = new OfpRenderer().render(
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000));

        assertThat(document.lines()).allSatisfy(line ->
                assertThat(line.length()).as("line: %s", line).isLessThanOrEqualTo(100));
    }

    @Test
    void a_plan_that_cannot_be_dispatched_says_why_on_the_document() {
        String document = new OfpRenderer().render(buildPlan(ConstantWeatherModel.isa(), 90, 30_000));

        assertThat(document)
                .contains("NOT DISPATCHABLE")
                .contains("ETOPS approval")
                .doesNotContain("DISPATCH RELEASE");
    }

    @Test
    void the_document_records_the_data_the_plan_was_built_on() {
        String document = new OfpRenderer().render(
                buildPlan(ConstantWeatherModel.isa(), 180, 30_000));

        assertThat(document).contains("AIRAC 2413").contains("ISA, no wind");
    }
}
