package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.arinc.Arinc424File;
import io.aerodyne.navdata.arinc.Arinc424Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationDatabaseTest {

    private static NavigationDatabase db;

    /** Somewhere over the English Channel — the "where the route has reached" of most tests. */
    private static final GeoPoint OVER_THE_CHANNEL = GeoPoint.of(50.5, -1.5);
    /** Over São Paulo. */
    private static final GeoPoint OVER_BRAZIL = GeoPoint.of(-23.0, -46.0);

    @BeforeAll
    static void loadTheExtract() {
        try (InputStream stream = NavigationDatabaseTest.class.getResourceAsStream("/arinc/extract.arinc");
             Reader reader = new InputStreamReader(stream, StandardCharsets.ISO_8859_1)) {
            Arinc424File file = new Arinc424Parser().parse(reader);
            db = NavigationDatabase.from(file);
        } catch (Exception e) {
            throw new AssertionError("could not load the test extract", e);
        }
    }

    @Test
    void carries_the_cycle_the_data_came_from() {
        assertThat(db.cycle()).contains(new AiracCycle(2024, 13));
    }

    @Test
    void indexes_airports_navaids_waypoints_and_runways_as_fixes() {
        assertThat(db.airportCount()).isEqualTo(3);
        assertThat(db.airwayCount()).isEqualTo(2);
        // Three airports, three runways, four navaids, five waypoints.
        assertThat(db.fixCount()).isEqualTo(15);
    }

    @Test
    void resolves_an_unambiguous_identifier_without_any_context() {
        assertThat(db.resolve("BEDEK", (GeoPoint) null))
                .map(Fix::kind)
                .contains(FixKind.WAYPOINT);
    }

    @Test
    void resolves_a_repeated_identifier_to_the_candidate_nearest_the_route() {
        assertThat(db.allNamed("KOLIN")).hasSize(2);

        assertThat(db.resolve("KOLIN", OVER_THE_CHANNEL))
                .map(Fix::icaoRegion)
                .contains("EG");
        assertThat(db.resolve("KOLIN", OVER_BRAZIL))
                .map(Fix::icaoRegion)
                .contains("SB");
    }

    @Test
    void refuses_to_guess_at_a_repeated_identifier_with_nothing_to_disambiguate_against() {
        assertThat(db.resolve("KOLIN", (GeoPoint) null)).isEmpty();
    }

    @Test
    void resolves_by_region_when_the_route_names_one() {
        assertThat(db.resolve("KOLIN", "SB"))
                .map(Fix::position)
                .map(GeoPoint::latitude)
                .hasValueSatisfying(lat -> assertThat(lat).isNegative());
    }

    @Test
    void a_coordinate_in_the_route_string_resolves_to_itself() {
        Fix fix = db.resolve("5130N", OVER_THE_CHANNEL).orElseThrow();

        assertThat(fix.kind()).isEqualTo(FixKind.COORDINATE);
        assertThat(fix.ident()).isEqualTo("5130N");
        assertThat(fix.position()).isEqualTo(GeoPoint.of(51.0, -30.0));
        assertThat(fix.icaoRegion()).isNull();
    }

    @Test
    void an_unknown_identifier_resolves_to_nothing() {
        assertThat(db.resolve("ZZZZZ", OVER_THE_CHANNEL)).isEmpty();
    }

    @Test
    void finds_an_airport_by_its_ICAO_code_case_insensitively() {
        assertThat(db.airport("egll")).map(Airport::name).contains("LONDON HEATHROW");
        assertThat(db.airport("ZZZZ")).isEmpty();
    }

    @Test
    void finds_the_fixes_near_a_position_nearest_first() {
        var near = db.fixesWithin(GeoPoint.of(51.3, -0.45), 40.0);

        assertThat(near).isNotEmpty();
        assertThat(near.get(0).ident()).isEqualTo("OCK");
        assertThat(near).extracting(Fix::ident).contains("EGLL", "LON");
    }

    @Test
    void a_radius_that_reaches_nothing_returns_nothing() {
        assertThat(db.fixesWithin(GeoPoint.of(0.0, 0.0), 100.0)).isEmpty();
    }

    @Test
    void finds_the_airports_near_a_position_for_alternate_selection() {
        var alternates = db.airportsWithin(GeoPoint.of(51.4, -0.4), 60.0);

        assertThat(alternates).extracting(Airport::icaoCode).containsExactly("EGLL", "EGKK");
    }

    @Test
    void limits_the_number_of_candidates_when_asked_to() {
        assertThat(db.airportsWithin(GeoPoint.of(51.4, -0.4), 60.0, 1))
                .extracting(Airport::icaoCode)
                .containsExactly("EGLL");
    }

    @Test
    void lists_the_airways_that_pass_through_a_fix() {
        assertThat(db.airwaysThrough("KOLIN")).extracting(Airway::ident).containsExactly("UN862");
        assertThat(db.airwaysThrough("OCK")).extracting(Airway::ident).containsExactly("L620");
        assertThat(db.airwaysThrough("EGLL")).isEmpty();
    }

    @Test
    void screens_alternates_on_runway_length_and_approach_capability() {
        Airport egll = db.airport("EGLL").orElseThrow();
        Airport egkk = db.airport("EGKK").orElseThrow();

        assertThat(egll.hasRunwayAtLeast(10_000)).isTrue();
        assertThat(egll.hasRunwayAtLeast(13_000)).isFalse();
        assertThat(egll.hasPrecisionApproach()).isTrue();
        // Gatwick has no runway records in this extract, so the airport field is what answers.
        assertThat(egkk.hasRunwayAtLeast(8_000)).isTrue();
        assertThat(egkk.hasPrecisionApproach()).isFalse();
    }

    @Test
    void a_runway_threshold_is_addressable_as_a_fix() {
        assertThat(db.allNamed("RW27L"))
                .singleElement()
                .satisfies(fix -> {
                    assertThat(fix.kind()).isEqualTo(FixKind.RUNWAY);
                    assertThat(fix.kind().isTerminal()).isTrue();
                    assertThat(fix.icaoRegion()).isEqualTo("EG");
                });
    }
}
