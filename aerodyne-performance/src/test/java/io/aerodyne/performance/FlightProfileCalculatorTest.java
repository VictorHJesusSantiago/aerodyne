package io.aerodyne.performance;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindVector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FlightProfileCalculatorTest {

    private static final AircraftType TYPE = AircraftTypes.narrowbodyTwin();
    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T08:00:00Z");

    /** Heathrow to Barcelona, near enough: a 600-nm sector on a roughly southerly track. */
    private static final GeoPoint EGLL = GeoPoint.of(51.4775, -0.461389);
    private static final GeoPoint LEBL = GeoPoint.of(41.297, 2.078);

    private FlightProfileCalculator with(WeatherModel weather) {
        return new FlightProfileCalculator(TYPE, weather);
    }

    private FlightProfileCalculator.Request request(WindVector unused) {
        return new FlightProfileCalculator.Request(List.of(EGLL, LEBL),
                70_000, 35_000, 83, 12, OFF_BLOCKS);
    }

    @Test
    void produces_a_profile_covering_the_whole_route() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        assertThat(profile.distanceNm())
                .isCloseTo(Geodesy.distanceNm(EGLL, LEBL), within(1.0));
        assertThat(profile.legs()).isNotEmpty();
        assertThat(profile.legs().get(0).from()).isEqualTo(EGLL);
        assertThat(profile.legs().get(profile.legs().size() - 1).to()).isEqualTo(LEBL);
    }

    @Test
    void the_profile_has_a_climb_a_cruise_and_a_descent() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        assertThat(profile.distanceNm(FlightPhase.CLIMB)).isPositive();
        assertThat(profile.distanceNm(FlightPhase.CRUISE)).isPositive();
        assertThat(profile.distanceNm(FlightPhase.DESCENT)).isPositive();
        assertThat(profile.distanceNm(FlightPhase.CLIMB)
                + profile.distanceNm(FlightPhase.CRUISE)
                + profile.distanceNm(FlightPhase.DESCENT))
                .isCloseTo(profile.distanceNm(), within(0.01));
    }

    @Test
    void the_trip_fuel_and_time_are_the_sum_of_the_legs() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        assertThat(profile.tripFuelKg()).isCloseTo(
                profile.legs().stream().mapToDouble(ProfileLeg::fuelKg).sum(), within(1e-6));
        assertThat(profile.tripTimeMinutes()).isCloseTo(
                profile.legs().stream().mapToDouble(ProfileLeg::timeMinutes).sum(), within(1e-6));
    }

    @Test
    void a_six_hundred_mile_sector_takes_a_plausible_time_and_fuel() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        // A narrowbody twin covers this in a bit over an hour and a half on around four tonnes.
        assertThat(profile.tripTimeMinutes()).isBetween(85.0, 115.0);
        assertThat(profile.tripFuelKg()).isBetween(3_000.0, 5_500.0);
        assertThat(profile.averageGroundSpeedKt()).isBetween(380.0, 470.0);
    }

    @Test
    void the_weight_falls_monotonically_as_the_flight_progresses() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        double previous = profile.takeoffWeightKg();
        for (ProfileLeg leg : profile.legs()) {
            assertThat(leg.weightAtEndKg()).isLessThanOrEqualTo(previous);
            previous = leg.weightAtEndKg();
        }
        assertThat(previous).isCloseTo(profile.landingWeightKg(), within(1e-6));
    }

    @Test
    void elapsed_time_and_cumulative_fuel_run_forward() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        double previousTime = 0.0;
        double previousFuel = 0.0;
        for (ProfileLeg leg : profile.legs()) {
            assertThat(leg.elapsedMinutes()).isGreaterThanOrEqualTo(previousTime);
            assertThat(leg.cumulativeFuelKg()).isGreaterThanOrEqualTo(previousFuel);
            previousTime = leg.elapsedMinutes();
            previousFuel = leg.cumulativeFuelKg();
        }
    }

    @Test
    void the_arrival_time_matches_the_integrated_trip_time() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        assertThat(profile.arrivesAt())
                .isEqualTo(OFF_BLOCKS.plusSeconds(Math.round(profile.tripTimeMinutes() * 60.0)));
    }

    @Test
    void a_headwind_costs_time_and_fuel_and_a_tailwind_saves_them() {
        // The track runs roughly south-south-east, so a northerly is a tailwind.
        FlightProfile still = with(ConstantWeatherModel.isa()).compute(request(null));
        FlightProfile tailwind = with(ConstantWeatherModel.of(WindVector.of(340.0, 60.0)))
                .compute(request(null));
        FlightProfile headwind = with(ConstantWeatherModel.of(WindVector.of(160.0, 60.0)))
                .compute(request(null));

        assertThat(tailwind.tripTimeMinutes()).isLessThan(still.tripTimeMinutes());
        assertThat(tailwind.tripFuelKg()).isLessThan(still.tripFuelKg());
        assertThat(headwind.tripTimeMinutes()).isGreaterThan(still.tripTimeMinutes());
        assertThat(headwind.tripFuelKg()).isGreaterThan(still.tripFuelKg());
    }

    @Test
    void the_average_headwind_has_the_sign_the_wind_implies() {
        FlightProfile headwind = with(ConstantWeatherModel.of(WindVector.of(160.0, 60.0)))
                .compute(request(null));
        FlightProfile tailwind = with(ConstantWeatherModel.of(WindVector.of(340.0, 60.0)))
                .compute(request(null));

        assertThat(headwind.averageHeadwindKt()).isPositive();
        assertThat(tailwind.averageHeadwindKt()).isNegative();
    }

    @Test
    void a_warmer_than_standard_atmosphere_costs_fuel() {
        FlightProfile standard = with(ConstantWeatherModel.isa()).compute(request(null));
        FlightProfile warm = with(new ConstantWeatherModel(WindVector.CALM, 15.0, Instant.EPOCH))
                .compute(request(null));

        assertThat(warm.tripFuelKg()).isGreaterThan(standard.tripFuelKg());
    }

    @Test
    void a_heavier_aircraft_burns_more_over_the_same_route() {
        FlightProfileCalculator calculator = with(ConstantWeatherModel.isa());

        FlightProfile light = calculator.compute(new FlightProfileCalculator.Request(
                List.of(EGLL, LEBL), 58_000, 35_000, 83, 12, OFF_BLOCKS));
        FlightProfile heavy = calculator.compute(new FlightProfileCalculator.Request(
                List.of(EGLL, LEBL), 76_000, 35_000, 83, 12, OFF_BLOCKS));

        assertThat(heavy.tripFuelKg()).isGreaterThan(light.tripFuelKg());
    }

    @Test
    void no_integration_step_is_longer_than_the_sampling_limit() {
        FlightProfile profile = with(ConstantWeatherModel.isa()).compute(request(null));

        assertThat(profile.legs())
                .allSatisfy(leg -> assertThat(leg.distanceNm())
                        .isLessThanOrEqualTo(FlightProfileCalculator.MAX_STEP_NM + 1e-6));
    }

    @Test
    void a_sector_too_short_to_reach_the_cruise_level_still_produces_a_profile() {
        // Fifty miles: not enough to climb to FL350 and come back down.
        GeoPoint nearby = Geodesy.direct(EGLL, 90.0,
                io.aerodyne.geo.Units.nauticalMilesToMetres(50.0));

        FlightProfile profile = with(ConstantWeatherModel.isa())
                .compute(new FlightProfileCalculator.Request(
                        List.of(EGLL, nearby), 65_000, 35_000, 83, 12, OFF_BLOCKS));

        assertThat(profile.distanceNm()).isCloseTo(50.0, within(1.0));
        assertThat(profile.distanceNm(FlightPhase.CRUISE)).isCloseTo(0.0, within(0.01));
        assertThat(profile.tripFuelKg()).isPositive();
    }

    @Test
    void a_multi_leg_route_is_integrated_leg_by_leg() {
        GeoPoint midpoint = Geodesy.midpoint(EGLL, LEBL);

        FlightProfile direct = with(ConstantWeatherModel.isa()).compute(request(null));
        FlightProfile viaMidpoint = with(ConstantWeatherModel.isa())
                .compute(new FlightProfileCalculator.Request(
                        List.of(EGLL, midpoint, LEBL), 70_000, 35_000, 83, 12, OFF_BLOCKS));

        assertThat(viaMidpoint.distanceNm()).isCloseTo(direct.distanceNm(), within(1.0));
        assertThat(viaMidpoint.tripFuelKg()).isCloseTo(direct.tripFuelKg(), within(50.0));
    }

    @Test
    void a_cruise_level_above_the_ceiling_is_capped_at_it() {
        FlightProfile profile = with(ConstantWeatherModel.isa())
                .compute(new FlightProfileCalculator.Request(
                        List.of(EGLL, LEBL), 70_000, 51_000, 83, 12, OFF_BLOCKS));

        assertThat(profile.cruiseAltitudeFt()).isEqualTo(TYPE.climb().ceilingFt());
    }

    @Test
    void says_whether_the_forecast_actually_covered_the_flight() {
        assertThat(with(ConstantWeatherModel.isa()).compute(request(null)).usedForecastWind())
                .isTrue();
    }

    @Test
    void refuses_a_route_with_a_single_point() {
        assertThatThrownBy(() -> new FlightProfileCalculator.Request(
                List.of(EGLL), 70_000, 35_000, 83, 12, OFF_BLOCKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two points");
    }

    @Test
    void refuses_a_cruise_level_below_the_departure_field() {
        assertThatThrownBy(() -> new FlightProfileCalculator.Request(
                List.of(EGLL, LEBL), 70_000, 5_000, 6_000, 12, OFF_BLOCKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below the departure field");
    }

    @Test
    void refuses_a_weightless_aircraft() {
        assertThatThrownBy(() -> new FlightProfileCalculator.Request(
                List.of(EGLL, LEBL), 0, 35_000, 83, 12, OFF_BLOCKS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takeoff weight");
    }
}
