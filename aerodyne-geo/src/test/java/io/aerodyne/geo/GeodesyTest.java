package io.aerodyne.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GeodesyTest {

    /** London Heathrow, threshold of runway 27L, from the UK AIP. */
    private static final GeoPoint EGLL = GeoPoint.of(51.4775, -0.461389);
    /** New York JFK. */
    private static final GeoPoint KJFK = GeoPoint.of(40.639751, -73.778925);
    /** Sydney Kingsford Smith. */
    private static final GeoPoint YSSY = GeoPoint.of(-33.946111, 151.177222);
    /** Guarulhos. */
    private static final GeoPoint SBGR = GeoPoint.of(-23.435556, -46.473056);

    @Nested
    @DisplayName("Vincenty inverse")
    class Inverse {

        @Test
        void reproduces_the_published_EGLL_KJFK_geodesic() {
            GeodesicSolution s = Geodesy.inverse(EGLL, KJFK);

            // Just under 3000 nm; the value is stable to the metre across Vincenty implementations.
            assertThat(s.distanceNm()).isCloseTo(2999.1, within(0.5));
            assertThat(s.initialBearingDeg()).isCloseTo(288.0, within(0.5));
            // A westbound North Atlantic crossing finishes on a much more southerly heading.
            assertThat(s.finalBearingDeg()).isCloseTo(231.4, within(0.5));
        }

        @Test
        void is_symmetric_in_distance_and_reciprocal_in_bearing() {
            GeodesicSolution out = Geodesy.inverse(EGLL, SBGR);
            GeodesicSolution back = Geodesy.inverse(SBGR, EGLL);

            assertThat(back.distanceMetres()).isCloseTo(out.distanceMetres(), within(1e-3));
            assertThat(back.finalBearingDeg())
                    .isCloseTo(Geodesy.reciprocalBearing(out.initialBearingDeg()), within(1e-6));
        }

        @Test
        void returns_zero_for_coincident_points() {
            GeodesicSolution s = Geodesy.inverse(EGLL, EGLL);

            assertThat(s.distanceMetres()).isZero();
            assertThat(s.initialBearingDeg()).isZero();
        }

        @Test
        void solves_an_equatorial_leg_where_cos2SigmaM_degenerates() {
            GeodesicSolution s = Geodesy.inverse(GeoPoint.of(0.0, 0.0), GeoPoint.of(0.0, 10.0));

            // Ten degrees along the equator on WGS-84: 10 * (a * pi / 180).
            assertThat(s.distanceMetres()).isCloseTo(1_113_194.9, within(1.0));
            assertThat(s.initialBearingDeg()).isCloseTo(90.0, within(1e-9));
        }

        @Test
        void refuses_to_guess_at_a_near_antipodal_pair() {
            GeoPoint p = GeoPoint.of(0.0, 0.0);
            GeoPoint antipode = GeoPoint.of(0.5, 179.7);

            assertThatThrownBy(() -> Geodesy.inverse(p, antipode))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("antipodal");
        }

        @Test
        void distanceNm_falls_back_to_the_sphere_when_Vincenty_will_not_converge() {
            GeoPoint p = GeoPoint.of(0.0, 0.0);
            GeoPoint antipode = GeoPoint.of(0.5, 179.7);

            // Half the circumference, near enough; the point is that it answers at all.
            assertThat(Geodesy.distanceNm(p, antipode)).isCloseTo(10_800.0, within(50.0));
        }
    }

    @Nested
    @DisplayName("Vincenty direct")
    class Direct {

        @Test
        void inverts_the_inverse_solution() {
            GeodesicSolution s = Geodesy.inverse(EGLL, YSSY);
            GeoPoint arrived = Geodesy.direct(EGLL, s.initialBearingDeg(), s.distanceMetres());

            assertThat(arrived.latitude()).isCloseTo(YSSY.latitude(), within(1e-8));
            assertThat(arrived.longitude()).isCloseTo(YSSY.longitude(), within(1e-8));
        }

        @Test
        void running_due_north_climbs_one_degree_per_sixty_nautical_miles_of_meridian() {
            GeoPoint north = Geodesy.direct(GeoPoint.of(0.0, 30.0), 0.0,
                    Units.nauticalMilesToMetres(600.0));

            assertThat(north.longitude()).isCloseTo(30.0, within(1e-9));
            // The meridian is shorter than 60 nm per degree near the equator, so slightly over 10.
            assertThat(north.latitude()).isCloseTo(10.05, within(0.05));
        }

        @Test
        void zero_distance_returns_the_origin_unchanged() {
            assertThat(Geodesy.direct(SBGR, 123.0, 0.0)).isEqualTo(SBGR);
        }
    }

    @Nested
    @DisplayName("spherical helpers")
    class Spherical {

        @Test
        void great_circle_stays_within_a_third_of_a_percent_of_the_ellipsoid() {
            double ellipsoidal = Geodesy.inverse(EGLL, KJFK).distanceNm();
            double spherical = Geodesy.greatCircleDistanceNm(EGLL, KJFK);

            assertThat(Math.abs(spherical - ellipsoidal) / ellipsoidal).isLessThan(0.003);
        }

        @Test
        void the_midpoint_of_a_transatlantic_leg_is_equidistant_from_both_ends() {
            GeoPoint mid = Geodesy.midpoint(EGLL, KJFK);

            assertThat(Geodesy.greatCircleDistanceNm(EGLL, mid))
                    .isCloseTo(Geodesy.greatCircleDistanceNm(mid, KJFK), within(1e-6));
        }

        @Test
        void intermediate_points_are_clamped_to_the_endpoints() {
            assertThat(Geodesy.intermediatePoint(EGLL, KJFK, -0.5)).isEqualTo(EGLL);
            assertThat(Geodesy.intermediatePoint(EGLL, KJFK, 1.5)).isEqualTo(KJFK);
        }

        @Test
        void a_point_on_the_track_has_no_cross_track_error() {
            GeoPoint onTrack = Geodesy.intermediatePoint(EGLL, KJFK, 0.4);

            assertThat(Geodesy.crossTrackDistanceNm(onTrack, EGLL, KJFK)).isCloseTo(0.0, within(1e-6));
        }

        @Test
        void cross_track_is_positive_to_the_right_of_track() {
            // Eastbound along the equator; a point in the southern hemisphere is to the right.
            GeoPoint start = GeoPoint.of(0.0, 0.0);
            GeoPoint end = GeoPoint.of(0.0, 20.0);
            GeoPoint south = GeoPoint.of(-1.0, 10.0);

            assertThat(Geodesy.crossTrackDistanceNm(south, start, end)).isGreaterThan(0.0);
            assertThat(Geodesy.crossTrackDistanceNm(GeoPoint.of(1.0, 10.0), start, end))
                    .isLessThan(0.0);
        }

        @Test
        void along_track_locates_the_foot_of_the_perpendicular() {
            GeoPoint start = GeoPoint.of(0.0, 0.0);
            GeoPoint end = GeoPoint.of(0.0, 20.0);
            GeoPoint abeam = GeoPoint.of(-1.0, 5.0);

            double legLength = Geodesy.greatCircleDistanceNm(start, end);
            assertThat(Geodesy.alongTrackDistanceNm(abeam, start, end))
                    .isCloseTo(legLength * 0.25, within(legLength * 0.01));
        }
    }

    @Nested
    @DisplayName("rhumb lines")
    class Rhumb {

        @Test
        void an_east_west_rhumb_line_is_longer_than_the_great_circle_at_high_latitude() {
            GeoPoint west = GeoPoint.of(60.0, -30.0);
            GeoPoint east = GeoPoint.of(60.0, 30.0);

            assertThat(Geodesy.rhumbDistanceNm(west, east))
                    .isGreaterThan(Geodesy.greatCircleDistanceNm(west, east));
        }

        @Test
        void an_east_west_rhumb_line_holds_a_course_of_090() {
            assertThat(Geodesy.rhumbBearingDeg(GeoPoint.of(45.0, 0.0), GeoPoint.of(45.0, 10.0)))
                    .isCloseTo(90.0, within(1e-9));
        }

        @Test
        void a_meridian_rhumb_line_matches_the_great_circle() {
            GeoPoint south = GeoPoint.of(10.0, 25.0);
            GeoPoint north = GeoPoint.of(40.0, 25.0);

            assertThat(Geodesy.rhumbDistanceNm(south, north))
                    .isCloseTo(Geodesy.greatCircleDistanceNm(south, north), within(1e-6));
        }
    }

    @Nested
    @DisplayName("bearing arithmetic")
    class Bearings {

        @Test
        void normalises_into_the_zero_to_360_range() {
            assertThat(Geodesy.normaliseBearing(-10.0)).isEqualTo(350.0);
            assertThat(Geodesy.normaliseBearing(370.0)).isEqualTo(10.0);
            assertThat(Geodesy.normaliseBearing(360.0)).isEqualTo(0.0);
        }

        @Test
        void reciprocal_wraps_through_north() {
            assertThat(Geodesy.reciprocalBearing(350.0)).isEqualTo(170.0);
            assertThat(Geodesy.reciprocalBearing(10.0)).isEqualTo(190.0);
        }

        @Test
        void the_turn_across_north_is_the_short_way_round() {
            assertThat(Geodesy.bearingDifference(350.0, 10.0)).isCloseTo(20.0, within(1e-9));
            assertThat(Geodesy.bearingDifference(10.0, 350.0)).isCloseTo(-20.0, within(1e-9));
        }

        @Test
        void a_reversal_is_reported_as_a_right_turn() {
            assertThat(Geodesy.bearingDifference(0.0, 180.0)).isCloseTo(180.0, within(1e-9));
        }
    }
}
