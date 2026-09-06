package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Airport;
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

class EtopsTest {

    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T08:00:00Z");
    /** A diversion at 400 kt from FL220, which is where a twin ends up on one engine. */
    private static final double DIVERSION_SPEED_KT = 400.0;
    private static final double DIVERSION_ALTITUDE_FT = 22_000.0;

    /** Shannon, Keflavik and Gander: the three that make a North Atlantic crossing possible. */
    private static final Airport EINN = airport("EINN", 52.702, -8.925);
    private static final Airport BIKF = airport("BIKF", 63.985, -22.605);
    private static final Airport CYQX = airport("CYQX", 48.937, -54.568);

    private static Airport airport(String icao, double latitude, double longitude) {
        return new Airport(icao, null, GeoPoint.of(latitude, longitude), 100, 6000,
                10_000, "C", icao, List.of());
    }

    private DiversionCalculator diversions(WeatherModel weather) {
        return new DiversionCalculator(weather, DIVERSION_SPEED_KT, DIVERSION_ALTITUDE_FT);
    }

    /** A transatlantic track from Ireland to Newfoundland, well out of gliding range of both. */
    private RoutePath northAtlantic() {
        return new RoutePath(List.of(
                GeoPoint.of(53.0, -10.0),
                GeoPoint.of(56.0, -25.0),
                GeoPoint.of(56.0, -40.0),
                GeoPoint.of(50.0, -52.0)));
    }

    @Nested
    @DisplayName("diversion times")
    class Diversions {

        @Test
        void a_still_air_diversion_is_distance_over_the_diversion_speed() {
            DiversionCalculator calculator = diversions(ConstantWeatherModel.isa());
            GeoPoint from = GeoPoint.of(56.0, -30.0);

            DiversionCalculator.Diversion diversion =
                    calculator.to(from, BIKF.position(), OFF_BLOCKS);

            assertThat(diversion.groundSpeedKt()).isCloseTo(DIVERSION_SPEED_KT, within(1e-6));
            assertThat(diversion.timeMinutes())
                    .isCloseTo(diversion.distanceNm() / DIVERSION_SPEED_KT * 60.0, within(1e-6));
        }

        @Test
        void a_headwind_makes_the_same_diversion_take_longer() {
            GeoPoint from = GeoPoint.of(56.0, -30.0);
            double course = Geodesy.initialBearingDeg(from, BIKF.position());

            double stillAir = diversions(ConstantWeatherModel.isa())
                    .timeMinutes(from, BIKF.position(), OFF_BLOCKS);
            double intoWind = diversions(ConstantWeatherModel.of(WindVector.of(course, 80.0)))
                    .timeMinutes(from, BIKF.position(), OFF_BLOCKS);

            assertThat(intoWind).isGreaterThan(stillAir);
            // 80 kt off 400 is a fifth of the speed, so a fifth or so more time.
            assertThat(intoWind / stillAir).isCloseTo(400.0 / 320.0, within(0.02));
        }

        @Test
        void a_diversion_to_where_you_already_are_takes_no_time() {
            DiversionCalculator.Diversion diversion = diversions(ConstantWeatherModel.isa())
                    .to(BIKF.position(), BIKF.position(), OFF_BLOCKS);

            assertThat(diversion.distanceNm()).isZero();
            assertThat(diversion.timeMinutes()).isZero();
            assertThat(diversion.isFlyable()).isTrue();
        }

        @Test
        void the_still_air_range_follows_from_the_approved_time() {
            assertThat(diversions(ConstantWeatherModel.isa()).rangeNm(180))
                    .isCloseTo(1200.0, within(1e-6));
        }

        @Test
        void reports_whether_a_diversion_fits_inside_an_approval() {
            DiversionCalculator.Diversion diversion = diversions(ConstantWeatherModel.isa())
                    .to(GeoPoint.of(56.0, -30.0), BIKF.position(), OFF_BLOCKS);

            assertThat(diversion.fitsWithinMinutes(180)).isTrue();
            assertThat(diversion.fitsWithinMinutes(20)).isFalse();
        }

        @Test
        void refuses_a_diversion_speed_of_zero() {
            assertThatThrownBy(() ->
                    new DiversionCalculator(ConstantWeatherModel.isa(), 0.0, 22_000.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("equal time points")
    class EqualTimePoints {

        @Test
        void the_still_air_equal_time_point_is_the_equal_distance_point() {
            EqualTimePointFinder finder = new EqualTimePointFinder(
                    northAtlantic(), diversions(ConstantWeatherModel.isa()));

            EqualTimePoint etp = finder.between(EINN.position(), "EINN", CYQX.position(), "CYQX",
                    OFF_BLOCKS, 450.0).orElseThrow();

            assertThat(etp.toFirst().distanceNm())
                    .isCloseTo(etp.toSecond().distanceNm(), within(5.0));
            assertThat(etp.imbalanceMinutes()).isLessThan(1.0);
        }

        @Test
        void a_wind_moves_the_equal_time_point_away_from_the_equal_distance_point() {
            RoutePath path = northAtlantic();
            EqualTimePoint stillAir = new EqualTimePointFinder(path,
                    diversions(ConstantWeatherModel.isa()))
                    .between(EINN.position(), "EINN", CYQX.position(), "CYQX", OFF_BLOCKS, 450.0)
                    .orElseThrow();
            EqualTimePoint windy = new EqualTimePointFinder(path,
                    diversions(ConstantWeatherModel.of(WindVector.of(270.0, 90.0))))
                    .between(EINN.position(), "EINN", CYQX.position(), "CYQX", OFF_BLOCKS, 450.0)
                    .orElseThrow();

            // A westerly is a tailwind back to Shannon and a headwind on to Gander, so at the
            // still-air point Gander is now further in time. The balance moves towards it.
            assertThat(windy.distanceAlongNm()).isGreaterThan(stillAir.distanceAlongNm());
            assertThat(windy.imbalanceMinutes()).isLessThan(1.0);
        }

        @Test
        void the_times_to_both_aerodromes_are_equal_at_the_point_it_finds() {
            EqualTimePoint etp = new EqualTimePointFinder(northAtlantic(),
                    diversions(ConstantWeatherModel.of(WindVector.of(300.0, 60.0))))
                    .between(EINN.position(), "EINN", CYQX.position(), "CYQX", OFF_BLOCKS, 450.0)
                    .orElseThrow();

            assertThat(etp.toFirst().timeMinutes())
                    .isCloseTo(etp.toSecond().timeMinutes(), within(1.0));
            assertThat(etp.diversionTimeMinutes()).isPositive();
        }

        @Test
        void the_point_lies_on_the_route() {
            RoutePath path = northAtlantic();
            EqualTimePoint etp = new EqualTimePointFinder(path, diversions(ConstantWeatherModel.isa()))
                    .between(EINN.position(), "EINN", CYQX.position(), "CYQX", OFF_BLOCKS, 450.0)
                    .orElseThrow();

            assertThat(etp.position()).isEqualTo(path.pointAtNm(etp.distanceAlongNm()));
            assertThat(etp.distanceAlongNm()).isBetween(0.0, path.totalDistanceNm());
        }

        @Test
        void says_which_way_to_go_either_side_of_the_point() {
            EqualTimePoint etp = new EqualTimePointFinder(northAtlantic(),
                    diversions(ConstantWeatherModel.isa()))
                    .between(EINN.position(), "EINN", CYQX.position(), "CYQX", OFF_BLOCKS, 450.0)
                    .orElseThrow();

            assertThat(etp.preferredBefore()).isEqualTo("EINN");
            assertThat(etp.preferredAfter()).isEqualTo("CYQX");
            assertThat(etp.label()).isEqualTo("ETP EINN/CYQX");
        }

        @Test
        void there_is_no_equal_time_point_when_one_aerodrome_is_always_nearer() {
            // Both aerodromes are off the eastern end, so Shannon is nearer for the whole route.
            EqualTimePointFinder finder = new EqualTimePointFinder(
                    northAtlantic(), diversions(ConstantWeatherModel.isa()));

            assertThat(finder.between(EINN.position(), "EINN",
                    GeoPoint.of(51.5, -0.5), "EGLL", OFF_BLOCKS, 450.0)).isEmpty();
        }

        @Test
        void the_critical_point_balances_the_departure_against_the_destination() {
            RoutePath path = northAtlantic();

            EqualTimePoint critical = new EqualTimePointFinder(path,
                    diversions(ConstantWeatherModel.isa()))
                    .criticalPoint("EINN", "CYQX", OFF_BLOCKS, 450.0).orElseThrow();

            assertThat(critical.distanceAlongNm())
                    .isCloseTo(path.totalDistanceNm() / 2.0, within(path.totalDistanceNm() * 0.02));
        }
    }

    @Nested
    @DisplayName("ETOPS assessment")
    class Assessment {

        private EtopsAnalysis analyse(WeatherModel weather, int approvedMinutes) {
            return new EtopsAnalyser(diversions(weather))
                    .analyse(northAtlantic(), List.of(EINN, BIKF, CYQX),
                            approvedMinutes, OFF_BLOCKS, 450.0);
        }

        @Test
        void a_transatlantic_route_leaves_and_re_enters_the_sixty_minute_area() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 180);

            assertThat(analysis.isWithinThreshold()).isFalse();
            assertThat(analysis.entryPoint()).isPresent();
            assertThat(analysis.exitPoint()).isPresent();
        }

        @Test
        void finds_the_point_furthest_in_time_from_any_adequate_aerodrome() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 180);

            assertThat(analysis.worstDiversionMinutes()).isGreaterThan(60.0);
            assertThat(analysis.adequateAerodromes()).containsExactly("EINN", "BIKF", "CYQX");
            assertThat(analysis.worstNearestIcao()).isIn("EINN", "BIKF", "CYQX");
        }

        @Test
        void a_route_inside_the_approval_is_compliant() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 180);

            assertThat(analysis.isCompliant()).isTrue();
            assertThat(analysis.marginMinutes()).isPositive();
            assertThat(analysis.summary()).contains("ETOPS 180");
        }

        @Test
        void the_same_route_fails_against_a_shorter_approval() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 60);

            assertThat(analysis.isCompliant()).isFalse();
            assertThat(analysis.marginMinutes()).isNegative();
            assertThat(analysis.summary()).contains("NOT COMPLIANT");
        }

        @Test
        void says_what_approval_the_route_would_need() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 60);

            assertThat(analysis.requiredApprovalMinutes())
                    .isGreaterThanOrEqualTo((int) Math.ceil(analysis.worstDiversionMinutes()))
                    .isIn(90, 120, 138, 180, 207, 240, 330);
        }

        @Test
        void a_headwind_on_the_diversion_lengthens_the_worst_case() {
            double stillAir = analyse(ConstantWeatherModel.isa(), 180).worstDiversionMinutes();
            double windy = analyse(ConstantWeatherModel.of(WindVector.of(270.0, 80.0)), 180)
                    .worstDiversionMinutes();

            assertThat(windy).isGreaterThan(stillAir);
        }

        @Test
        void a_short_domestic_route_never_leaves_the_threshold_area() {
            RoutePath shortRoute = new RoutePath(List.of(
                    GeoPoint.of(52.7, -8.9), GeoPoint.of(53.3, -6.3)));

            EtopsAnalysis analysis = new EtopsAnalyser(diversions(ConstantWeatherModel.isa()))
                    .analyse(shortRoute, List.of(EINN), 180, OFF_BLOCKS, 450.0);

            assertThat(analysis.isWithinThreshold()).isTrue();
            assertThat(analysis.entryPoint()).isEmpty();
            assertThat(analysis.summary()).contains("stays within 60 minutes");
        }

        @Test
        void computes_the_equal_time_points_between_successive_aerodromes() {
            EtopsAnalysis analysis = analyse(ConstantWeatherModel.isa(), 180);

            assertThat(analysis.equalTimePoints()).isNotEmpty();
            assertThat(analysis.equalTimePoints())
                    .allSatisfy(etp -> assertThat(etp.imbalanceMinutes()).isLessThan(1.0));
        }

        @Test
        void refuses_to_assess_a_route_with_nowhere_to_divert_to() {
            assertThatThrownBy(() -> new EtopsAnalyser(diversions(ConstantWeatherModel.isa()))
                    .analyse(northAtlantic(), List.of(), 180, OFF_BLOCKS, 450.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no adequate aerodrome");
        }
    }

    @Nested
    @DisplayName("RoutePath")
    class Path {

        @Test
        void measures_the_route_leg_by_leg() {
            RoutePath path = northAtlantic();

            double summed = 0.0;
            for (int i = 0; i < path.points().size() - 1; i++) {
                summed += Geodesy.distanceNm(path.points().get(i), path.points().get(i + 1));
            }
            assertThat(path.totalDistanceNm()).isCloseTo(summed, within(1e-6));
        }

        @Test
        void locates_a_point_a_given_distance_along() {
            RoutePath path = northAtlantic();

            assertThat(path.pointAtNm(0)).isEqualTo(path.start());
            assertThat(path.pointAtNm(path.totalDistanceNm())).isEqualTo(path.end());
            assertThat(Geodesy.distanceNm(path.start(), path.pointAtNm(200.0)))
                    .isCloseTo(200.0, within(1.0));
        }

        @Test
        void clamps_a_query_beyond_either_end() {
            RoutePath path = northAtlantic();

            assertThat(path.pointAtNm(-100)).isEqualTo(path.start());
            assertThat(path.pointAtNm(99_999)).isEqualTo(path.end());
        }

        @Test
        void samples_the_whole_route_including_both_ends() {
            RoutePath path = northAtlantic();

            List<RoutePath.Sample> samples = path.sample(100.0);

            assertThat(samples.get(0).position()).isEqualTo(path.start());
            assertThat(samples.get(samples.size() - 1).position()).isEqualTo(path.end());
            assertThat(samples).hasSizeGreaterThan((int) (path.totalDistanceNm() / 100.0));
        }

        @Test
        void refuses_a_route_that_is_not_one() {
            assertThatThrownBy(() -> new RoutePath(List.of(GeoPoint.of(50.0, 0.0))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least two points");
            assertThatThrownBy(() -> new RoutePath(
                    List.of(GeoPoint.of(50.0, 0.0), GeoPoint.of(50.0, 0.0))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("some length");
        }
    }
}
