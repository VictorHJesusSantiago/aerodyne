package io.aerodyne.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WindTest {

    @Nested
    @DisplayName("WindVector")
    class Vector {

        @Test
        void a_westerly_wind_blows_eastward() {
            WindVector westerly = WindVector.of(270.0, 50.0);

            assertThat(westerly.eastwardKt()).isCloseTo(50.0, within(1e-9));
            assertThat(westerly.northwardKt()).isCloseTo(0.0, within(1e-9));
            assertThat(westerly.blowingTowardsDeg()).isCloseTo(90.0, within(1e-9));
        }

        @Test
        void a_northerly_wind_blows_southward() {
            WindVector northerly = WindVector.of(360.0, 30.0);

            assertThat(northerly.northwardKt()).isCloseTo(-30.0, within(1e-9));
            assertThat(northerly.eastwardKt()).isCloseTo(0.0, within(1e-9));
        }

        @ParameterizedTest
        @CsvSource({"0, 20", "45, 35", "90, 15", "180, 60", "270, 45", "315, 25"})
        void round_trips_through_its_components(double direction, double speed) {
            WindVector original = WindVector.of(direction, speed);

            WindVector back = WindVector.fromComponents(
                    original.eastwardKt(), original.northwardKt());

            assertThat(back.directionDeg()).isCloseTo(direction % 360.0, within(1e-6));
            assertThat(back.speedKt()).isCloseTo(speed, within(1e-9));
        }

        @Test
        void calm_has_no_direction_to_speak_of() {
            assertThat(WindVector.fromComponents(0.0, 0.0)).isEqualTo(WindVector.CALM);
            assertThat(WindVector.of(270.0, 0.0)).isEqualTo(WindVector.CALM);
        }

        @Test
        void rejects_a_negative_speed() {
            assertThatThrownBy(() -> WindVector.of(270.0, -5.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        void a_wind_straight_down_the_track_is_all_headwind() {
            WindVector wind = WindVector.of(270.0, 40.0);

            assertThat(wind.headwindComponentKt(270.0)).isCloseTo(40.0, within(1e-9));
            assertThat(wind.tailwindComponentKt(270.0)).isCloseTo(-40.0, within(1e-9));
            assertThat(wind.crosswindComponentKt(270.0)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        void a_wind_from_behind_is_a_tailwind() {
            assertThat(WindVector.of(270.0, 40.0).tailwindComponentKt(90.0))
                    .isCloseTo(40.0, within(1e-9));
        }

        @Test
        void a_wind_from_the_left_gives_a_positive_crosswind() {
            // Track 360, wind from 270: the wind is on the left, pushing the aircraft right.
            assertThat(WindVector.of(270.0, 30.0).crosswindComponentKt(360.0))
                    .isCloseTo(30.0, within(1e-9));
            assertThat(WindVector.of(90.0, 30.0).crosswindComponentKt(360.0))
                    .isCloseTo(-30.0, within(1e-9));
        }

        @Test
        void averaging_winds_either_side_of_north_does_not_produce_a_southerly() {
            WindVector mean = WindVector.mean(
                    List.of(WindVector.of(350.0, 40.0), WindVector.of(10.0, 40.0)));

            assertThat(mean.directionDeg()).isCloseTo(0.0, within(1e-6));
            assertThat(mean.speedKt()).isCloseTo(39.4, within(0.2));
        }

        @Test
        void the_mean_of_no_winds_is_calm() {
            assertThat(WindVector.mean(List.of())).isEqualTo(WindVector.CALM);
        }

        @Test
        void interpolation_is_clamped_to_the_endpoints() {
            WindVector a = WindVector.of(270.0, 40.0);
            WindVector b = WindVector.of(90.0, 20.0);

            assertThat(a.interpolateTo(b, 0.0)).isEqualTo(a);
            assertThat(a.interpolateTo(b, 1.0).directionDeg()).isCloseTo(90.0, within(1e-6));
            assertThat(a.interpolateTo(b, -1.0)).isEqualTo(a);
        }

        @Test
        void interpolating_between_opposing_winds_passes_through_calm() {
            WindVector halfway = WindVector.of(270.0, 40.0)
                    .interpolateTo(WindVector.of(90.0, 40.0), 0.5);

            assertThat(halfway.speedKt()).isCloseTo(0.0, within(1e-6));
        }

        @Test
        void renders_the_five_digit_form_an_OFP_prints() {
            assertThat(WindVector.of(270.0, 50.0).toOfpString()).isEqualTo("27050");
            assertThat(WindVector.of(5.0, 8.0).toOfpString()).isEqualTo("00508");
            assertThat(WindVector.of(360.0, 12.0).toOfpString()).isEqualTo("00012");
        }
    }

    @Nested
    @DisplayName("WindTriangle")
    class Triangle {

        @Test
        void a_pure_headwind_slows_the_aircraft_and_needs_no_drift_correction() {
            WindTriangleSolution s =
                    WindTriangle.solve(90.0, 450.0, WindVector.of(90.0, 50.0));

            assertThat(s.groundSpeedKt()).isCloseTo(400.0, within(1e-9));
            assertThat(s.trueHeadingDeg()).isCloseTo(90.0, within(1e-9));
            assertThat(s.windCorrectionAngleDeg()).isCloseTo(0.0, within(1e-9));
            assertThat(s.headwindComponentKt()).isCloseTo(50.0, within(1e-9));
        }

        @Test
        void a_pure_tailwind_speeds_it_up() {
            assertThat(WindTriangle.groundSpeedKt(90.0, 450.0, WindVector.of(270.0, 50.0)))
                    .isCloseTo(500.0, within(1e-9));
        }

        @Test
        void a_crosswind_from_the_left_needs_a_left_turn_into_it() {
            // Track 360, wind from 270 at 50 kt: the aircraft must head left of track, into it.
            WindTriangleSolution s =
                    WindTriangle.solve(360.0, 450.0, WindVector.of(270.0, 50.0));

            assertThat(s.windCorrectionAngleDeg()).isCloseTo(6.38, within(0.05));
            assertThat(s.trueHeadingDeg()).isCloseTo(6.38, within(0.05));
            assertThat(s.crosswindComponentKt()).isCloseTo(50.0, within(1e-9));
            // A pure crosswind costs a little ground speed, because part of the airspeed is
            // spent holding the track.
            assertThat(s.groundSpeedKt()).isCloseTo(447.2, within(0.2));
        }

        @Test
        void a_crosswind_from_the_right_needs_a_right_turn_into_it() {
            WindTriangleSolution s =
                    WindTriangle.solve(360.0, 450.0, WindVector.of(90.0, 50.0));

            assertThat(s.windCorrectionAngleDeg()).isCloseTo(-6.38, within(0.05));
            assertThat(s.trueHeadingDeg()).isCloseTo(353.62, within(0.05));
        }

        @Test
        void calm_air_means_heading_equals_track_and_ground_speed_equals_airspeed() {
            WindTriangleSolution s = WindTriangle.solve(123.0, 450.0, WindVector.CALM);

            assertThat(s.trueHeadingDeg()).isCloseTo(123.0, within(1e-9));
            assertThat(s.groundSpeedKt()).isCloseTo(450.0, within(1e-9));
        }

        @Test
        void converts_a_leg_distance_into_a_time() {
            WindTriangleSolution s =
                    WindTriangle.solve(90.0, 480.0, WindVector.of(90.0, 60.0));

            assertThat(s.timeHours(420.0)).isCloseTo(1.0, within(1e-9));
            assertThat(s.timeMinutes(210.0)).isCloseTo(30.0, within(1e-9));
        }

        @Test
        void a_headwind_stronger_than_the_airspeed_makes_no_progress() {
            WindTriangleSolution s =
                    WindTriangle.solve(90.0, 100.0, WindVector.of(90.0, 120.0));

            assertThat(s.isFlyable()).isFalse();
            assertThatThrownBy(() -> s.timeHours(100.0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not flyable");
        }

        @Test
        void refuses_a_crosswind_no_heading_can_hold() {
            assertThatThrownBy(() ->
                    WindTriangle.solve(360.0, 100.0, WindVector.of(270.0, 150.0)))
                    .isInstanceOf(WindTriangle.UnsolvableWindException.class)
                    .hasMessageContaining("crosswind exceeds the airspeed");
        }

        @Test
        void rejects_a_zero_airspeed() {
            assertThatThrownBy(() -> WindTriangle.solve(90.0, 0.0, WindVector.CALM))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("airspeed");
        }
    }
}
