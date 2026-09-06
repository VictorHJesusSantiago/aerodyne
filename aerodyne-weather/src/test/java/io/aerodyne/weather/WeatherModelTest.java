package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WeatherModelTest {

    @Nested
    @DisplayName("StandardAtmosphere")
    class Atmosphere {

        @ParameterizedTest(name = "{0} hPa is about FL{1}")
        @CsvSource({
                "1013.25, 0",
                "850, 4781",
                "500, 18289",
                "300, 30065",
                "250, 33999",
                "200, 38662",
                "150, 44647",
        })
        void maps_isobaric_surfaces_onto_the_flight_levels_they_sit_at(double hpa, double feet) {
            assertThat(StandardAtmosphere.pressureAltitudeFt(hpa)).isCloseTo(feet, within(15.0));
        }

        @ParameterizedTest
        @CsvSource({"0", "5000", "18000", "35000", "41000", "50000"})
        void the_conversion_is_exactly_invertible(double altitudeFt) {
            double pressure = StandardAtmosphere.pressureHpa(altitudeFt);

            assertThat(StandardAtmosphere.pressureAltitudeFt(pressure))
                    .isCloseTo(altitudeFt, within(1e-6));
        }

        @Test
        void is_continuous_across_the_tropopause() {
            double justBelow = StandardAtmosphere.pressureAltitudeFt(
                    StandardAtmosphere.TROPOPAUSE_PRESSURE_HPA + 0.001);
            double justAbove = StandardAtmosphere.pressureAltitudeFt(
                    StandardAtmosphere.TROPOPAUSE_PRESSURE_HPA - 0.001);

            assertThat(justAbove - justBelow).isLessThan(1.0);
        }

        @Test
        void sea_level_density_matches_the_standard_value() {
            assertThat(StandardAtmosphere.densityKgPerM3(0.0, 15.0))
                    .isCloseTo(StandardAtmosphere.SEA_LEVEL_DENSITY, within(0.001));
        }

        @Test
        void a_hot_day_raises_the_density_altitude_above_the_pressure_altitude() {
            double hot = StandardAtmosphere.densityAltitudeFt(5000.0, 35.0);
            double standard = StandardAtmosphere.densityAltitudeFt(
                    5000.0, io.aerodyne.geo.Units.isaTemperatureC(5000.0));

            assertThat(standard).isCloseTo(5000.0, within(20.0));
            assertThat(hot).isGreaterThan(7000.0);
        }

        @Test
        void rejects_a_pressure_of_zero() {
            assertThatThrownBy(() -> StandardAtmosphere.pressureAltitudeFt(0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("LatLonGrid")
    class Grid {

        /** A 3x3 grid over 40-42N, 10-12E, scanning southward from the top-left. */
        private LatLonGrid grid() {
            return new LatLonGrid(42.0, 10.0, -1.0, 1.0, 3, 3,
                    new float[]{0, 1, 2, 10, 11, 12, 20, 21, 22});
        }

        @Test
        void a_query_on_a_node_returns_that_node() {
            assertThat(grid().sample(GeoPoint.of(41.0, 11.0))).isCloseTo(11.0, within(1e-6));
            assertThat(grid().sample(GeoPoint.of(42.0, 10.0))).isCloseTo(0.0, within(1e-6));
            assertThat(grid().sample(GeoPoint.of(40.0, 12.0))).isCloseTo(22.0, within(1e-6));
        }

        @Test
        void interpolates_between_nodes() {
            assertThat(grid().sample(GeoPoint.of(41.5, 10.5))).isCloseTo(5.5, within(1e-6));
        }

        @Test
        void clamps_a_query_outside_a_regional_grid_to_its_edge() {
            assertThat(grid().sample(GeoPoint.of(50.0, 10.0))).isCloseTo(0.0, within(1e-6));
            assertThat(grid().sample(GeoPoint.of(41.0, 30.0))).isCloseTo(12.0, within(1e-6));
        }

        @Test
        void reports_which_positions_it_actually_covers() {
            assertThat(grid().covers(GeoPoint.of(41.0, 11.0))).isTrue();
            assertThat(grid().covers(GeoPoint.of(50.0, 11.0))).isFalse();
            assertThat(grid().covers(GeoPoint.of(41.0, 30.0))).isFalse();
            assertThat(grid().wrapsLongitude()).isFalse();
        }

        @Test
        void a_global_grid_wraps_across_the_antimeridian() {
            // Four columns of 90 degrees each: 0E, 90E, 180E, 270E.
            LatLonGrid global = new LatLonGrid(10.0, 0.0, -10.0, 90.0, 4, 2,
                    new float[]{0, 10, 20, 30, 0, 10, 20, 30});

            assertThat(global.wrapsLongitude()).isTrue();
            assertThat(global.covers(GeoPoint.of(0.0, -170.0))).isTrue();
            // Halfway between the 270E column (value 30) and the 0E column (value 0).
            assertThat(global.sample(GeoPoint.of(10.0, -45.0))).isCloseTo(15.0, within(1e-6));
        }

        @Test
        void maps_every_value_through_a_transform() {
            LatLonGrid doubled = grid().transformed(v -> v * 2.0);

            assertThat(doubled.valueAt(1, 1)).isCloseTo(22.0, within(1e-6));
            assertThat(doubled.positionAt(1, 1)).isEqualTo(grid().positionAt(1, 1));
        }

        @Test
        void refuses_a_value_array_that_does_not_match_the_geometry() {
            assertThatThrownBy(() -> new LatLonGrid(42.0, 10.0, -1.0, 1.0, 3, 3, new float[8]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected 9 values");
        }

        @Test
        void refuses_a_grid_too_small_to_interpolate_across() {
            assertThatThrownBy(() -> new LatLonGrid(42.0, 10.0, -1.0, 1.0, 1, 3, new float[3]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2x2");
        }
    }

    @Nested
    @DisplayName("GriddedWeatherModel")
    class Gridded {

        private static final Instant T0 = Instant.parse("2024-06-01T00:00:00Z");
        private static final Instant T6 = Instant.parse("2024-06-01T06:00:00Z");
        private static final GeoPoint MIDDLE = GeoPoint.of(41.0, 11.0);

        /** A level whose wind is uniform, so the tests exercise the vertical and time axes. */
        private UpperAirLevel level(double hpa, double eastwardKt, double temperatureC) {
            return new UpperAirLevel(hpa,
                    uniform(eastwardKt), uniform(0.0), uniform(temperatureC));
        }

        private LatLonGrid uniform(double value) {
            float[] values = new float[9];
            java.util.Arrays.fill(values, (float) value);
            return new LatLonGrid(42.0, 10.0, -1.0, 1.0, 3, 3, values);
        }

        private GriddedWeatherModel twoLevelsTwoHours() {
            return GriddedWeatherModel.builder()
                    .issuedAt(T0)
                    .source("test forecast")
                    .hour(T0, List.of(level(500, 40.0, -20.0), level(250, 80.0, -50.0)))
                    .hour(T6, List.of(level(500, 60.0, -22.0), level(250, 120.0, -52.0)))
                    .build();
        }

        @Test
        void reports_the_levels_and_times_it_holds() {
            GriddedWeatherModel model = twoLevelsTwoHours();

            assertThat(model.pressureLevelsHpa()).containsExactly(500.0, 250.0);
            assertThat(model.validTimes()).containsExactly(T0, T6);
            assertThat(model.verticalCoverage()).isEqualTo("FL183 to FL340");
        }

        @Test
        void returns_a_level_unchanged_when_the_query_sits_on_it() {
            AtmosphericConditions at250 = twoLevelsTwoHours()
                    .at(MIDDLE, StandardAtmosphere.pressureAltitudeFt(250), T0);

            assertThat(at250.wind().speedKt()).isCloseTo(80.0, within(0.1));
            assertThat(at250.temperatureC()).isCloseTo(-50.0, within(0.1));
        }

        @Test
        void interpolates_between_levels_on_pressure_altitude() {
            double lower = StandardAtmosphere.pressureAltitudeFt(500);
            double upper = StandardAtmosphere.pressureAltitudeFt(250);

            AtmosphericConditions halfway = twoLevelsTwoHours()
                    .at(MIDDLE, (lower + upper) / 2.0, T0);

            assertThat(halfway.wind().speedKt()).isCloseTo(60.0, within(0.5));
            assertThat(halfway.temperatureC()).isCloseTo(-35.0, within(0.5));
        }

        @Test
        void does_not_extrapolate_above_the_top_level_or_below_the_bottom() {
            GriddedWeatherModel model = twoLevelsTwoHours();

            assertThat(model.at(MIDDLE, 60_000.0, T0).wind().speedKt())
                    .isCloseTo(80.0, within(0.1));
            assertThat(model.at(MIDDLE, 0.0, T0).wind().speedKt())
                    .isCloseTo(40.0, within(0.1));
        }

        @Test
        void interpolates_between_forecast_hours() {
            AtmosphericConditions midway = twoLevelsTwoHours().at(
                    MIDDLE, StandardAtmosphere.pressureAltitudeFt(250),
                    Instant.parse("2024-06-01T03:00:00Z"));

            assertThat(midway.wind().speedKt()).isCloseTo(100.0, within(0.5));
            assertThat(midway.temperatureC()).isCloseTo(-51.0, within(0.1));
        }

        @Test
        void holds_the_last_forecast_beyond_the_end_rather_than_extrapolating() {
            AtmosphericConditions later = twoLevelsTwoHours().at(
                    MIDDLE, StandardAtmosphere.pressureAltitudeFt(250),
                    Instant.parse("2024-06-02T00:00:00Z"));

            assertThat(later.wind().speedKt()).isCloseTo(120.0, within(0.1));
        }

        @Test
        void says_when_a_query_falls_outside_what_the_forecast_covers() {
            GriddedWeatherModel model = twoLevelsTwoHours();

            assertThat(model.covers(MIDDLE, T0)).isTrue();
            assertThat(model.covers(MIDDLE, T6.plusSeconds(3600))).isTrue();
            assertThat(model.covers(MIDDLE, T6.plusSeconds(4 * 3600))).isFalse();
            assertThat(model.covers(GeoPoint.of(0.0, 0.0), T0)).isFalse();
        }

        @Test
        void refuses_to_build_a_model_with_no_forecast_in_it() {
            assertThatThrownBy(() -> GriddedWeatherModel.builder().build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one forecast hour");
        }
    }

    @Nested
    @DisplayName("ConstantWeatherModel")
    class Constant {

        @Test
        void the_ISA_model_is_calm_and_standard_everywhere() {
            AtmosphericConditions at350 = ConstantWeatherModel.isa()
                    .at(GeoPoint.of(51.0, -30.0), 35_000.0, Instant.EPOCH);

            assertThat(at350.wind()).isEqualTo(WindVector.CALM);
            assertThat(at350.temperatureC()).isCloseTo(-54.3, within(0.2));
            assertThat(at350.isaDeviationC(35_000.0)).isCloseTo(0.0, within(1e-6));
        }

        @Test
        void applies_a_uniform_ISA_deviation() {
            ConstantWeatherModel warm = new ConstantWeatherModel(
                    WindVector.of(270.0, 60.0), 10.0, Instant.EPOCH);

            AtmosphericConditions at350 =
                    warm.at(GeoPoint.of(51.0, -30.0), 35_000.0, Instant.EPOCH);

            assertThat(at350.isaDeviationC(35_000.0)).isCloseTo(10.0, within(1e-6));
            assertThat(at350.trueAirspeedKt(0.85)).isGreaterThan(
                    ConstantWeatherModel.isa().at(GeoPoint.of(51.0, -30.0), 35_000.0, Instant.EPOCH)
                            .trueAirspeedKt(0.85));
        }

        @Test
        void covers_everywhere_because_it_is_not_a_forecast() {
            assertThat(ConstantWeatherModel.isa().covers(GeoPoint.of(0.0, 0.0), Instant.EPOCH))
                    .isTrue();
            assertThat(ConstantWeatherModel.isa().source()).isEqualTo("ISA, no wind");
        }
    }
}
