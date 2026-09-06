package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.NavigationDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteExpanderTest {

    private final NavigationDatabase database = TestNetwork.build();
    private final RouteExpander expander = new RouteExpander(database);

    @Test
    void expands_a_route_of_direct_legs() {
        ExpandedRoute route = expander.expand("EGXX ALFA BRAVO CHARL EGYY", TestNetwork.EGXX);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "BRAVO", "CHARL", "EGYY");
        assertThat(route.elements()).allMatch(RouteElement::isDirect);
        assertThat(route.isComplete()).isTrue();
    }

    @Test
    void an_airway_leg_pulls_in_the_fixes_between_its_ends() {
        ExpandedRoute route = expander.expand("EGXX ALFA UM1 DELTA EGYY", TestNetwork.EGXX);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "BRAVO", "CHARL", "DELTA", "EGYY");
        assertThat(route.airways()).containsExactly("UM1");
    }

    @Test
    void the_fixes_an_airway_crosses_are_marked_as_not_filed() {
        ExpandedRoute route = expander.expand("EGXX ALFA UM1 DELTA EGYY", TestNetwork.EGXX);

        assertThat(route.filedPoints()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "DELTA", "EGYY");
    }

    @Test
    void an_airway_can_be_flown_backwards_when_it_permits_it() {
        ExpandedRoute route = expander.expand("ECHO UM1 ALFA", TestNetwork.ECHO);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("ECHO", "DELTA", "CHARL", "BRAVO", "ALFA");
    }

    @Test
    void a_one_way_airway_flown_backwards_becomes_a_direct_leg_with_a_warning() {
        ExpandedRoute route = expander.expand("DELTA UN7 CHARL", TestNetwork.DELTA);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("DELTA", "CHARL");
        assertThat(route.elements().get(1).isDirect()).isTrue();
        assertThat(route.warnings())
                .anyMatch(w -> w.message().contains("in that direction"));
    }

    @Test
    void an_airway_that_does_not_pass_through_the_entry_fix_becomes_a_direct_leg() {
        ExpandedRoute route = expander.expand("NORTH UM1 DELTA", TestNetwork.NORTH);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("NORTH", "DELTA");
        assertThat(route.warnings())
                .anyMatch(w -> w.message().contains("does not pass through NORTH"));
    }

    @Test
    void a_repeated_identifier_resolves_to_the_one_near_the_route() {
        ExpandedRoute nearEurope = expander.expand("ALFA CHARL", TestNetwork.ALFA);
        ExpandedRoute nearBrazil = expander.expand("CHARL", GeoPoint.of(-22.0, -45.0));

        assertThat(nearEurope.elements().get(1).position()).isEqualTo(TestNetwork.CHARL);
        assertThat(nearBrazil.elements().get(0).position().latitude()).isNegative();
    }

    @Test
    void skips_speed_and_level_groups() {
        ExpandedRoute route = expander.expand("EGXX N0450F350 ALFA UM1 DELTA F370 EGYY",
                TestNetwork.EGXX);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "BRAVO", "CHARL", "DELTA", "EGYY");
        assertThat(route.warnings()).isEmpty();
    }

    @Test
    void strips_a_level_change_appended_to_a_fix() {
        ExpandedRoute route = expander.expand("ALFA/N0450F350 BRAVO", TestNetwork.ALFA);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("ALFA", "BRAVO");
    }

    @Test
    void skips_DCT_and_the_other_routing_keywords() {
        ExpandedRoute route = expander.expand("EGXX DCT ALFA DCT BRAVO DCT EGYY", TestNetwork.EGXX);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "BRAVO", "EGYY");
    }

    @Test
    void a_coordinate_in_the_route_becomes_a_point_of_it() {
        ExpandedRoute route = expander.expand("ALFA 5000N00100W BRAVO", TestNetwork.ALFA);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("ALFA", "5000N00100W", "BRAVO");
        assertThat(route.elements().get(1).position()).isEqualTo(GeoPoint.of(50.0, -1.0));
    }

    @Test
    void drops_a_fix_that_is_not_in_the_database_and_says_so() {
        ExpandedRoute route = expander.expand("ALFA ZULU9 BRAVO", TestNetwork.ALFA);

        assertThat(route.elements()).extracting(RouteElement::ident)
                .containsExactly("ALFA", "BRAVO");
        assertThat(route.isComplete()).isFalse();
        assertThat(route.warnings())
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.token()).isEqualTo("ZULU9");
                    assertThat(w.dropped()).isTrue();
                    assertThat(w.message()).contains("no fix, navaid or airport");
                });
    }

    @Test
    void says_when_an_identifier_is_ambiguous_rather_than_guessing() {
        ExpandedRoute route = expander.expand("CHARL");

        assertThat(route.elements()).isEmpty();
        assertThat(route.warnings())
                .anyMatch(w -> w.message().contains("share this identifier"));
    }

    @Test
    void measures_the_route_and_how_much_longer_it_is_than_the_direct_track() {
        ExpandedRoute direct = expander.expand("ALFA UM1 DELTA", TestNetwork.ALFA);
        ExpandedRoute detour = expander.expand("ALFA UM2 DELTA", TestNetwork.ALFA);

        assertThat(direct.distanceNm()).isPositive();
        assertThat(detour.distanceNm()).isGreaterThan(direct.distanceNm());
        assertThat(detour.excessOverDirectNm()).isGreaterThan(direct.excessOverDirectNm());
    }

    @Test
    void renders_an_expanded_route_back_into_a_filed_route_string() {
        ExpandedRoute route = expander.expand("EGXX ALFA UM1 DELTA EGYY", TestNetwork.EGXX);

        assertThat(route.toRouteString()).isEqualTo("EGXX ALFA UM1 DELTA EGYY");
    }

    @Test
    void an_empty_route_expands_to_nothing_rather_than_failing() {
        assertThat(expander.expand("", TestNetwork.EGXX).elements()).isEmpty();
        assertThat(expander.expand(null, TestNetwork.EGXX).elements()).isEmpty();
    }

    @Test
    void the_path_is_what_the_fuel_integration_flies() {
        ExpandedRoute route = expander.expand("EGXX ALFA UM1 ECHO EGYY", TestNetwork.EGXX);

        assertThat(route.path()).hasSize(route.elements().size());
        assertThat(route.path().get(0)).isEqualTo(TestNetwork.EGXX);
        assertThat(route.path().get(route.path().size() - 1)).isEqualTo(TestNetwork.EGYY);
    }
}
