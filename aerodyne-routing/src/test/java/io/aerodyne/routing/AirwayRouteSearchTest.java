package io.aerodyne.routing;

import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.NavigationDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AirwayRouteSearchTest {

    private final NavigationDatabase database = TestNetwork.build();

    private Fix fix(String ident) {
        return database.resolve(ident, TestNetwork.ALFA).orElseThrow();
    }

    private AirwayGraph graphAt(double altitudeFt) {
        return AirwayGraph.build(database, altitudeFt);
    }

    @Test
    void the_graph_holds_only_what_is_flyable_at_its_altitude() {
        AirwayGraph high = graphAt(35_000);
        AirwayGraph low = graphAt(10_000);

        assertThat(high.altitudeFt()).isEqualTo(35_000.0);
        assertThat(high.nodeCount()).isEqualTo(6);
        // UL9 is a low-level airway, so at FL350 it contributes nothing.
        assertThat(high.edgesFrom(fix("BRAVO")))
                .extracting(AirwayGraph.Edge::airway)
                .doesNotContain("UL9");
        assertThat(low.edgesFrom(fix("BRAVO")))
                .extracting(AirwayGraph.Edge::airway)
                .contains("UL9");
    }

    @Test
    void a_two_way_airway_gives_an_edge_in_each_direction() {
        AirwayGraph graph = graphAt(35_000);

        assertThat(graph.edgesFrom(fix("BRAVO")))
                .extracting(edge -> edge.to().ident())
                .contains("ALFA", "CHARL");
    }

    @Test
    void a_one_way_airway_gives_an_edge_only_forwards() {
        AirwayGraph graph = graphAt(35_000);

        assertThat(graph.edgesFrom(fix("CHARL")))
                .filteredOn(edge -> edge.airway().equals("UN7"))
                .extracting(edge -> edge.to().ident())
                .containsExactly("DELTA");
        assertThat(graph.edgesFrom(fix("DELTA")))
                .noneMatch(edge -> edge.airway().equals("UN7"));
    }

    @Test
    void finds_the_shortest_route_between_two_fixes() {
        AirwayRouteSearch search = new AirwayRouteSearch(graphAt(35_000));

        AirwayRouteSearch.Result result = search.search(fix("ALFA"), fix("ECHO"));

        assertThat(result.found()).isTrue();
        assertThat(result.elements()).extracting(RouteElement::ident)
                .containsExactly("ALFA", "BRAVO", "CHARL", "DELTA", "ECHO");
        assertThat(result.airways()).containsExactly("UM1");
    }

    @Test
    void the_route_it_finds_is_no_longer_than_any_other_route() {
        AirwayRouteSearch search = new AirwayRouteSearch(graphAt(35_000));

        AirwayRouteSearch.Result viaChain = search.search(fix("ALFA"), fix("DELTA"));
        double viaNorth = Geodesy.greatCircleDistanceNm(TestNetwork.ALFA, TestNetwork.NORTH)
                + Geodesy.greatCircleDistanceNm(TestNetwork.NORTH, TestNetwork.DELTA);

        assertThat(viaChain.distanceNm()).isLessThan(viaNorth);
        assertThat(viaChain.airways()).containsExactly("UM1");
    }

    @Test
    void the_distance_it_reports_is_the_sum_of_its_legs() {
        AirwayRouteSearch.Result result =
                new AirwayRouteSearch(graphAt(35_000)).search(fix("ALFA"), fix("ECHO"));

        double summed = 0.0;
        for (int i = 0; i < result.elements().size() - 1; i++) {
            summed += Geodesy.greatCircleDistanceNm(
                    result.elements().get(i).position(), result.elements().get(i + 1).position());
        }
        assertThat(result.distanceNm()).isCloseTo(summed, org.assertj.core.api.Assertions.within(1e-6));
    }

    @Test
    void routes_the_long_way_round_when_the_direct_chain_is_unavailable() {
        // At FL150 only the low-level airway is flyable, and it does not reach ECHO.
        AirwayRouteSearch search = new AirwayRouteSearch(graphAt(15_000));

        assertThat(search.search(fix("BRAVO"), fix("CHARL")).airways()).containsExactly("UL9");
        assertThat(search.search(fix("ALFA"), fix("ECHO")).found()).isFalse();
    }

    @Test
    void a_route_to_the_fix_it_starts_at_is_a_route_of_one_point() {
        AirwayRouteSearch.Result result =
                new AirwayRouteSearch(graphAt(35_000)).search(fix("ALFA"), fix("ALFA"));

        assertThat(result.elements()).hasSize(1);
        assertThat(result.distanceNm()).isZero();
    }

    @Test
    void a_fix_that_is_not_on_the_network_yields_no_route() {
        AirwayRouteSearch.Result result = new AirwayRouteSearch(graphAt(35_000))
                .search(database.airport("EGXX").orElseThrow(), fix("ECHO"));

        assertThat(result.found()).isFalse();
    }

    @Test
    void joins_the_network_at_the_nearest_fix_to_an_aerodrome() {
        AirwayRouteSearch.Result result = new AirwayRouteSearch(graphAt(35_000))
                .searchNear(database.airport("EGXX").orElseThrow(),
                        database.airport("EGYY").orElseThrow(), 100.0);

        assertThat(result.found()).isTrue();
        assertThat(result.elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "BRAVO", "CHARL", "DELTA", "ECHO", "EGYY");
    }

    @Test
    void refuses_to_join_the_network_from_too_far_away() {
        AirwayRouteSearch.Result result = new AirwayRouteSearch(graphAt(35_000))
                .searchNear(database.airport("EGXX").orElseThrow(),
                        database.airport("EGYY").orElseThrow(), 1.0);

        assertThat(result.found()).isFalse();
    }

    @Test
    void renders_the_route_it_found_as_a_filed_route_string() {
        AirwayRouteSearch.Result result =
                new AirwayRouteSearch(graphAt(35_000)).search(fix("ALFA"), fix("ECHO"));

        assertThat(result.toRouteString()).isEqualTo("ALFA UM1 ECHO");
    }

    @Test
    void the_route_it_finds_expands_back_to_the_same_points() {
        AirwayRouteSearch.Result found =
                new AirwayRouteSearch(graphAt(35_000)).search(fix("ALFA"), fix("ECHO"));

        ExpandedRoute reExpanded =
                new RouteExpander(database).expand(found.toRouteString(), TestNetwork.ALFA);

        assertThat(reExpanded.path()).isEqualTo(
                found.elements().stream().map(RouteElement::position).toList());
    }

    @Test
    void reports_how_much_work_the_search_did() {
        AirwayRouteSearch.Result result =
                new AirwayRouteSearch(graphAt(35_000)).search(fix("ALFA"), fix("ECHO"));

        assertThat(result.nodesExpanded()).isPositive();
        assertThat(result.nodesExpanded()).isLessThanOrEqualTo(graphAt(35_000).nodeCount());
    }

    @Test
    void gives_up_rather_than_running_forever_when_the_node_limit_is_reached() {
        AirwayRouteSearch capped = new AirwayRouteSearch(graphAt(35_000), 2);

        assertThat(capped.search(fix("ALFA"), fix("ECHO")).found()).isFalse();
    }
}
