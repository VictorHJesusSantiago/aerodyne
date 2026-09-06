package io.aerodyne.performance;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Step climbs: cruising higher as the fuel burns off.
 *
 * <p>A long sector is used throughout, because the whole point of a step is that it needs enough
 * cruise left to earn back what the climb costs — and a short sector correctly declines to take
 * one, which is itself one of the things worth pinning down here.
 */
class StepClimbTest {

    private static final AircraftType TYPE = AircraftTypes.narrowbodyTwin();
    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T08:00:00Z");

    /** Dublin to Boston: 2670 nm, long enough for two steps to have somewhere to happen. */
    private static final GeoPoint EIDW = GeoPoint.of(53.421389, -6.270000);
    private static final GeoPoint KBOS = GeoPoint.of(42.362944, -71.006389);
    /** Heathrow to Barcelona: 600 nm, too short to be worth stepping on. */
    private static final GeoPoint EGLL = GeoPoint.of(51.477500, -0.461389);
    private static final GeoPoint LEBL = GeoPoint.of(41.297000, 2.078000);

    private static FlightProfile fly(List<GeoPoint> path, double weightKg, double levelFt,
                                     StepClimbPolicy policy, WeatherModel weather) {
        return new FlightProfileCalculator(TYPE, weather).compute(
                new FlightProfileCalculator.Request(path, weightKg, levelFt, 240, 20,
                        OFF_BLOCKS, policy));
    }

    private static FlightProfile longSector(StepClimbPolicy policy) {
        return fly(List.of(EIDW, KBOS), 74_000, 31_000, policy, ConstantWeatherModel.isa());
    }

    @Nested
    @DisplayName("the policy itself")
    class Policy {

        @Test
        void the_none_policy_permits_nothing() {
            assertThat(StepClimbPolicy.NONE.isEnabled()).isFalse();
        }

        @Test
        void the_standard_policy_is_two_thousand_feet_at_a_time() {
            StepClimbPolicy standard = StepClimbPolicy.standard();

            assertThat(standard.isEnabled()).isTrue();
            assertThat(standard.incrementFt()).isEqualTo(2000.0);
            assertThat(standard.maximumSteps()).isEqualTo(2);
        }

        @Test
        void a_downward_step_is_not_a_step() {
            assertThatThrownBy(() -> new StepClimbPolicy(-2000.0, 200.0, 200.0, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("downwards");
        }

        @Test
        void a_zero_increment_is_the_same_as_not_stepping() {
            assertThat(new StepClimbPolicy(0.0, 200.0, 200.0, 5).isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("what a step does to the profile")
    class Profile {

        @Test
        void a_long_sector_steps_up_when_it_is_allowed_to() {
            FlightProfile stepped = longSector(StepClimbPolicy.standard());

            assertThat(stepped.stepClimbCount()).isPositive();
            assertThat(stepped.cruiseLevelsFt()).hasSizeGreaterThan(1);
            assertThat(stepped.finalCruiseLevelFt()).isGreaterThan(31_000.0);
        }

        @Test
        void the_same_sector_holds_one_level_when_it_is_not() {
            FlightProfile level = longSector(StepClimbPolicy.NONE);

            assertThat(level.stepClimbCount()).isZero();
            assertThat(level.cruiseLevelsFt()).containsExactly(31_000.0);
            assertThat(level.finalCruiseLevelFt()).isEqualTo(31_000.0);
        }

        @Test
        void the_levels_only_ever_go_up_and_by_the_policy_increment() {
            List<Double> levels = longSector(StepClimbPolicy.standard()).cruiseLevelsFt();

            for (int i = 1; i < levels.size(); i++) {
                assertThat(levels.get(i) - levels.get(i - 1)).isEqualTo(2000.0);
            }
        }

        @Test
        void no_more_steps_are_taken_than_the_policy_allows() {
            FlightProfile capped = fly(List.of(EIDW, KBOS), 74_000, 29_000,
                    new StepClimbPolicy(1000.0, 100.0, 100.0, 1), ConstantWeatherModel.isa());

            assertThat(capped.stepClimbCount()).isLessThanOrEqualTo(1);
        }

        @Test
        void a_short_sector_declines_the_step_it_cannot_earn_back() {
            FlightProfile shortSector = fly(List.of(EGLL, LEBL), 70_000, 31_000,
                    StepClimbPolicy.standard(), ConstantWeatherModel.isa());

            assertThat(shortSector.stepClimbCount()).isZero();
        }

        @Test
        void a_step_never_takes_the_aircraft_above_its_ceiling() {
            FlightProfile atCeiling = fly(List.of(EIDW, KBOS), 62_000,
                    TYPE.climb().ceilingFt(), StepClimbPolicy.standard(),
                    ConstantWeatherModel.isa());

            assertThat(atCeiling.legs())
                    .allSatisfy(leg -> assertThat(leg.altitudeFt())
                            .isLessThanOrEqualTo(TYPE.climb().ceilingFt()));
        }
    }

    @Nested
    @DisplayName("what a step is worth")
    class Worth {

        @Test
        void stepping_burns_less_than_holding_the_lower_level() {
            double stepped = longSector(StepClimbPolicy.standard()).tripFuelKg();
            double level = longSector(StepClimbPolicy.NONE).tripFuelKg();

            assertThat(stepped).isLessThan(level);
        }

        @Test
        void the_saving_is_real_but_modest_a_percent_or_two_of_the_trip() {
            double stepped = longSector(StepClimbPolicy.standard()).tripFuelKg();
            double level = longSector(StepClimbPolicy.NONE).tripFuelKg();

            double saving = (level - stepped) / level;
            assertThat(saving).isBetween(0.0005, 0.05);
        }
    }

    @Nested
    @DisplayName("the profile still adds up")
    class Consistency {

        @Test
        void the_legs_still_cover_the_whole_route_exactly_once() {
            FlightProfile stepped = longSector(StepClimbPolicy.standard());
            FlightProfile level = longSector(StepClimbPolicy.NONE);

            assertThat(stepped.distanceNm()).isCloseTo(level.distanceNm(), within(0.5));
            assertThat(stepped.legs().get(0).from()).isEqualTo(EIDW);
            assertThat(stepped.legs().get(stepped.legs().size() - 1).to()).isEqualTo(KBOS);
        }

        @Test
        void the_totals_are_the_sum_of_the_legs_step_climbs_included() {
            FlightProfile stepped = longSector(StepClimbPolicy.standard());

            assertThat(stepped.tripFuelKg()).isCloseTo(
                    stepped.legs().stream().mapToDouble(ProfileLeg::fuelKg).sum(), within(0.01));
            assertThat(stepped.tripTimeMinutes()).isCloseTo(
                    stepped.legs().stream().mapToDouble(ProfileLeg::timeMinutes).sum(),
                    within(0.01));
        }

        @Test
        void a_step_climb_is_counted_apart_from_the_initial_climb() {
            FlightProfile stepped = longSector(StepClimbPolicy.standard());

            assertThat(stepped.fuelKg(FlightPhase.STEP_CLIMB)).isPositive();
            assertThat(stepped.fuelKg(FlightPhase.CLIMB)).isCloseTo(
                    longSector(StepClimbPolicy.NONE).fuelKg(FlightPhase.CLIMB), within(1.0));
        }

        @Test
        void the_weight_still_only_ever_falls() {
            FlightProfile stepped = longSector(StepClimbPolicy.standard());

            double previous = Double.MAX_VALUE;
            for (ProfileLeg leg : stepped.legs()) {
                assertThat(leg.weightAtEndKg()).isLessThanOrEqualTo(previous);
                previous = leg.weightAtEndKg();
            }
        }
    }

    @Nested
    @DisplayName("the wind is in the decision")
    class Wind {

        /**
         * The step is decided on ground distance, so a level whose wind is worse can lose even
         * though its still-air efficiency is better. The uniform model used here gives the same
         * wind at both levels, so what this pins down is the weaker claim that the decision
         * survives a strong wind at all rather than throwing or stepping every leg.
         */
        @Test
        void a_strong_headwind_does_not_break_the_decision() {
            FlightProfile intoWind = fly(List.of(EIDW, KBOS), 74_000, 31_000,
                    StepClimbPolicy.standard(),
                    ConstantWeatherModel.of(WindVector.of(270, 120)));

            assertThat(intoWind.tripFuelKg()).isPositive();
            assertThat(intoWind.stepClimbCount()).isLessThanOrEqualTo(2);
        }

        @Test
        void the_step_leg_carries_the_wind_it_was_flown_in() {
            FlightProfile stepped = fly(List.of(EIDW, KBOS), 74_000, 31_000,
                    StepClimbPolicy.standard(),
                    ConstantWeatherModel.of(WindVector.of(270, 60)));

            assertThat(stepped.legs())
                    .filteredOn(leg -> leg.phase() == FlightPhase.STEP_CLIMB)
                    .allSatisfy(leg -> assertThat(leg.wind().speedKt()).isEqualTo(60.0));
        }
    }
}
