package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.weather.AtmosphericConditions;
import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wind-optimal search, tested where it differs from the distance one.
 *
 * <p>{@link TestNetwork} has exactly the shape this needs: a short direct chain from ALFA to
 * DELTA, and a much longer detour through NORTH. The distance search always takes the chain.
 * Put a headwind on the chain and leave the detour in calm air, and the quickest route becomes
 * the long one — which is the entire claim being made.
 */
class WindOptimalRouteSearchTest {

    private static final Instant DEPARTURE = Instant.parse("2024-06-15T08:00:00Z");
    private static final double CRUISE_FT = 35_000.0;
    private static final double AIRSPEED_KT = 450.0;

    private final NavigationDatabase database = TestNetwork.build();

    private Fix fix(String ident) {
        return database.resolve(ident, TestNetwork.ALFA).orElseThrow();
    }

    private AirwayGraph graph() {
        return AirwayGraph.build(database, CRUISE_FT);
    }

    private WindOptimalRouteSearch searchIn(WeatherModel weather) {
        return new WindOptimalRouteSearch(graph(), weather, CRUISE_FT, AIRSPEED_KT, DEPARTURE);
    }

    /**
     * A headwind confined to a corridor along the direct chain.
     *
     * <p>The chain's fixes all lie on the line {@code latitude + longitude = 50}; NORTH does
     * not, and neither does the midpoint of either leg through it. So a wind switched on inside
     * a narrow band around that line hits the chain and misses the detour entirely.
     *
     * <p>250 knots is stronger than a real jet stream core, deliberately: the point of the test
     * is that the preference reverses, and a marginal wind would make it a test of arithmetic
     * rounding instead.
     */
    private record CorridorHeadwind(double speedKt) implements WeatherModel {

        private static final double CORRIDOR_HALF_WIDTH = 0.6;

        @Override
        public AtmosphericConditions at(GeoPoint position, double altitudeFt, Instant validAt) {
            return new AtmosphericConditions(windAt(position), -50.0);
        }

        private WindVector windAt(GeoPoint position) {
            double offLine = Math.abs(position.latitude() + position.longitude() - 50.0);
            // The chain runs south-east, so a wind from the south-east is a pure headwind on it.
            return offLine < CORRIDOR_HALF_WIDTH
                    ? WindVector.of(135.0, speedKt)
                    : WindVector.CALM;
        }

        @Override
        public boolean covers(GeoPoint position, Instant validAt) {
            return true;
        }

        @Override
        public Instant issuedAt() {
            return DEPARTURE;
        }

        @Override
        public String source() {
            return "test corridor";
        }

        @Override
        public double strongestWindKt() {
            return speedKt;
        }
    }

    @Nested
    @DisplayName("with no wind it agrees with the distance search")
    class NoWind {

        @Test
        void it_finds_the_same_route() {
            WindOptimalRouteSearch.Result quickest =
                    searchIn(ConstantWeatherModel.isa()).search(fix("ALFA"), fix("ECHO"));
            AirwayRouteSearch.Result shortest =
                    new AirwayRouteSearch(graph()).search(fix("ALFA"), fix("ECHO"));

            assertThat(quickest.found()).isTrue();
            assertThat(quickest.elements()).extracting(RouteElement::ident)
                    .containsExactlyElementsOf(
                            shortest.elements().stream().map(RouteElement::ident).toList());
        }

        @Test
        void the_time_it_reports_is_the_distance_at_the_airspeed() {
            WindOptimalRouteSearch.Result result =
                    searchIn(ConstantWeatherModel.isa()).search(fix("ALFA"), fix("ECHO"));

            assertThat(result.timeMinutes())
                    .isCloseTo(result.distanceNm() / AIRSPEED_KT * 60.0,
                            org.assertj.core.api.Assertions.within(0.5));
            assertThat(result.averageGroundSpeedKt())
                    .isCloseTo(AIRSPEED_KT, org.assertj.core.api.Assertions.within(1.0));
        }
    }

    @Nested
    @DisplayName("with a wind it does not")
    class WithWind {

        @Test
        void it_flies_round_a_headwind_the_distance_search_flies_through() {
            WindOptimalRouteSearch.Result quickest =
                    searchIn(new CorridorHeadwind(250.0)).search(fix("ALFA"), fix("DELTA"));

            assertThat(quickest.found()).isTrue();
            assertThat(quickest.elements()).extracting(RouteElement::ident)
                    .containsExactly("ALFA", "NORTH", "DELTA");
            assertThat(quickest.airways()).containsExactly("UM2");
        }

        @Test
        void the_distance_search_still_takes_the_short_way_through_it() {
            AirwayRouteSearch.Result shortest =
                    new AirwayRouteSearch(graph()).search(fix("ALFA"), fix("DELTA"));

            assertThat(shortest.elements()).extracting(RouteElement::ident)
                    .containsExactly("ALFA", "BRAVO", "CHARL", "DELTA");
        }

        @Test
        void the_route_it_picks_is_longer_and_quicker_which_is_the_whole_point() {
            WindOptimalRouteSearch.Result quickest =
                    searchIn(new CorridorHeadwind(250.0)).search(fix("ALFA"), fix("DELTA"));
            AirwayRouteSearch.Result shortest =
                    new AirwayRouteSearch(graph()).search(fix("ALFA"), fix("DELTA"));

            assertThat(quickest.distanceNm()).isGreaterThan(shortest.distanceNm());

            // Time the short route through the same wind, and it comes out slower.
            double throughTheWind = shortest.distanceNm()
                    / (AIRSPEED_KT - 250.0) * 60.0;
            assertThat(quickest.timeMinutes()).isLessThan(throughTheWind);
        }

        @Test
        void a_light_wind_is_not_worth_a_detour() {
            WindOptimalRouteSearch.Result quickest =
                    searchIn(new CorridorHeadwind(20.0)).search(fix("ALFA"), fix("DELTA"));

            assertThat(quickest.elements()).extracting(RouteElement::ident)
                    .containsExactly("ALFA", "BRAVO", "CHARL", "DELTA");
        }
    }

    @Nested
    @DisplayName("the guarantees it makes")
    class Guarantees {

        /**
         * A* only returns the true optimum while the heuristic never overestimates. The check
         * that matters is therefore not that the search returns <em>a</em> route but that no
         * other route in this network is quicker, so the result is compared against every route
         * the network actually offers.
         */
        @Test
        void nothing_the_network_offers_is_quicker_than_what_it_returns() {
            WeatherModel weather = new CorridorHeadwind(250.0);
            WindOptimalRouteSearch.Result quickest =
                    searchIn(weather).search(fix("ALFA"), fix("DELTA"));

            double viaChain = timeOf(weather, "ALFA", "BRAVO", "CHARL", "DELTA");
            double viaNorth = timeOf(weather, "ALFA", "NORTH", "DELTA");

            assertThat(quickest.timeMinutes())
                    .isLessThanOrEqualTo(Math.min(viaChain, viaNorth) + 0.001);
        }

        /** The time to fly a named sequence of fixes, computed independently of the search. */
        private double timeOf(WeatherModel weather, String... idents) {
            double minutes = 0.0;
            for (int i = 0; i < idents.length - 1; i++) {
                GeoPoint from = fix(idents[i]).position();
                GeoPoint to = fix(idents[i + 1]).position();
                double distance = io.aerodyne.geo.Geodesy.greatCircleDistanceNm(from, to);
                Instant at = DEPARTURE.plusSeconds(Math.round(minutes * 60.0));
                WindVector wind = weather.windAt(
                        io.aerodyne.geo.Geodesy.midpoint(from, to), CRUISE_FT, at);
                double groundSpeed = io.aerodyne.weather.WindTriangle.groundSpeedKt(
                        io.aerodyne.geo.Geodesy.initialBearingDeg(from, to), AIRSPEED_KT, wind);
                minutes += distance / groundSpeed * 60.0;
            }
            return minutes;
        }

        @Test
        void an_airspeed_of_zero_is_refused_rather_than_dividing_by_it() {
            assertThatThrownBy(() -> new WindOptimalRouteSearch(
                    graph(), ConstantWeatherModel.isa(), CRUISE_FT, 0.0, DEPARTURE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("true airspeed");
        }

        @Test
        void a_one_way_airway_is_no_more_flyable_backwards_for_having_a_tailwind() {
            WindOptimalRouteSearch.Result backwards =
                    searchIn(ConstantWeatherModel.of(WindVector.of(90.0, 100.0)))
                            .search(fix("DELTA"), fix("CHARL"));

            assertThat(backwards.airways()).doesNotContain("UN7");
        }

        @Test
        void a_fix_the_network_does_not_hold_gives_no_route_rather_than_an_error() {
            WindOptimalRouteSearch.Result none = searchIn(ConstantWeatherModel.isa())
                    .search(fix("ALFA"), database.resolve("CHARL", GeoPoint.of(-23.0, -46.0))
                            .orElseThrow());

            assertThat(none.found()).isFalse();
        }
    }

    @Nested
    @DisplayName("joining the network")
    class Joining {

        @Test
        void it_reaches_the_nearest_node_from_an_aerodrome_and_times_that_leg_too() {
            Fix departure = database.airport("EGXX").orElseThrow();
            Fix destination = database.airport("EGYY").orElseThrow();

            WindOptimalRouteSearch.Result result = searchIn(ConstantWeatherModel.isa())
                    .searchNear(departure, destination, 100.0);

            assertThat(result.found()).isTrue();
            assertThat(result.elements()).extracting(RouteElement::ident)
                    .startsWith("EGXX").endsWith("EGYY");
            assertThat(result.timeMinutes()).isGreaterThan(0.0);
            assertThat(result.averageGroundSpeedKt())
                    .isCloseTo(AIRSPEED_KT, org.assertj.core.api.Assertions.within(2.0));
        }

        @Test
        void nothing_within_reach_of_the_network_gives_no_route() {
            Fix faraway = io.aerodyne.navdata.Waypoint.enroute(
                    "FARAW", "SB", GeoPoint.of(-23.0, -46.0));

            WindOptimalRouteSearch.Result result = searchIn(ConstantWeatherModel.isa())
                    .searchNear(faraway, fix("ECHO"), 100.0);

            assertThat(result.found()).isFalse();
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void the_same_forecast_gives_the_same_route_every_time() {
            WeatherModel weather = new CorridorHeadwind(250.0);
            String first = searchIn(weather).search(fix("ALFA"), fix("ECHO")).toRouteString();

            for (int run = 0; run < 5; run++) {
                assertThat(searchIn(weather).search(fix("ALFA"), fix("ECHO")).toRouteString())
                        .isEqualTo(first);
            }
        }
    }
}
