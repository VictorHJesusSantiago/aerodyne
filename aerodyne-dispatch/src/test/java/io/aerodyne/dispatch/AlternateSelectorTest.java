package io.aerodyne.dispatch;

import io.aerodyne.dispatch.notam.Notam;
import io.aerodyne.dispatch.notam.NotamParser;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Runway;
import io.aerodyne.weather.metar.TafParser;
import io.aerodyne.weather.metar.TafReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlternateSelectorTest {

    private static final Instant ARRIVAL = Instant.parse("2024-06-15T14:00:00Z");
    private static final GeoPoint DESTINATION = GeoPoint.of(51.5, -0.5);

    /** The destination itself, plus four aerodromes at increasing distance from it. */
    private final NavigationDatabase database = NavigationDatabase.builder()
            .cycle(new AiracCycle(2024, 13))
            .airports(List.of(
                    airport("EGLL", 51.4775, -0.4614, 12_800, true),
                    airport("EGKK", 51.1481, -0.1903, 10_800, true),
                    airport("EGSS", 51.8850, 0.2350, 10_000, true),
                    // Short runway: rules itself out on the ground alone.
                    airport("EGKB", 51.3306, 0.0325, 5_900, true),
                    // No precision approach.
                    airport("EGMD", 50.9564, 0.9394, 9_700, false)))
            .build();

    private final AlternateSelector selector = new AlternateSelector(database);
    private final TafParser tafs = new TafParser(Instant.parse("2024-06-15T11:00:00Z"));

    private static Airport airport(String icao, double latitude, double longitude,
                                   int runwayFt, boolean precisionApproach) {
        Runway runway = new Runway(icao, "RW09", GeoPoint.of(latitude, longitude), 90.0,
                runwayFt, 150, 100, 0, 0.0, precisionApproach ? "I" + icao.substring(1) : null);
        return new Airport(icao, null, GeoPoint.of(latitude, longitude), 100, 6000,
                runwayFt, "C", icao, List.of(runway));
    }

    /** A forecast good enough for anything. */
    private TafReport goodWeather(String icao) {
        return tafs.parse("TAF " + icao + " 151100Z 1512/1618 25012KT CAVOK");
    }

    /** A forecast below alternate minima throughout. */
    private TafReport badWeather(String icao) {
        return tafs.parse("TAF " + icao + " 151100Z 1512/1618 25012KT 1200 BR BKN003");
    }

    /** Good prevailing conditions with a temporary deterioration below minima. */
    private TafReport marginalWeather(String icao) {
        return tafs.parse("TAF " + icao + " 151100Z 1512/1618 25012KT 9999 SCT025 "
                + "TEMPO 1513/1516 1500 BR BKN004");
    }

    private Map<String, TafReport> forecastsFor(String... icaos) {
        Map<String, TafReport> forecasts = new LinkedHashMap<>();
        for (String icao : icaos) {
            forecasts.put(icao, goodWeather(icao));
        }
        return forecasts;
    }

    @Test
    void ranks_usable_alternates_by_distance() {
        List<AlternateCandidate> usable = selector.select(DESTINATION,
                AlternateCriteria.forJetWithPrecisionApproach(),
                forecastsFor("EGLL", "EGKK", "EGSS", "EGKB", "EGMD"),
                List.of(), ARRIVAL);

        assertThat(usable).extracting(AlternateCandidate::icaoCode)
                .containsExactly("EGLL", "EGKK", "EGSS");
        assertThat(usable.get(0).distanceNm()).isLessThan(usable.get(1).distanceNm());
    }

    @Test
    void rejects_an_aerodrome_whose_runway_is_too_short_and_says_so() {
        AlternateCandidate egkb = screenFor("EGKB");

        assertThat(egkb.isUsable()).isFalse();
        assertThat(egkb.rejections()).anyMatch(r -> r.contains("no runway of 6000 ft"));
    }

    @Test
    void rejects_an_aerodrome_with_no_precision_approach_when_one_is_required() {
        AlternateCandidate egmd = screenFor("EGMD");

        assertThat(egmd.rejections()).contains("no precision approach");
    }

    @Test
    void accepts_a_non_precision_aerodrome_against_the_higher_minima_for_one() {
        List<AlternateCandidate> usable = selector.select(DESTINATION,
                AlternateCriteria.forNonPrecisionApproach(),
                forecastsFor("EGLL", "EGKK", "EGSS", "EGKB", "EGMD"),
                List.of(), ARRIVAL);

        assertThat(usable).extracting(AlternateCandidate::icaoCode).contains("EGMD");
    }

    @Test
    void rejects_an_aerodrome_forecast_below_the_planning_minima() {
        Map<String, TafReport> forecasts = forecastsFor("EGLL", "EGSS");
        forecasts.put("EGKK", badWeather("EGKK"));

        AlternateCandidate egkk = screen(forecasts, List.of()).stream()
                .filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();

        assertThat(egkk.isUsable()).isFalse();
        assertThat(egkk.rejections()).anyMatch(r -> r.contains("visibility 1200 m is below"));
    }

    @Test
    void holds_an_alternate_against_the_worst_case_of_its_forecast_not_the_prevailing() {
        Map<String, TafReport> forecasts = forecastsFor("EGLL", "EGSS");
        forecasts.put("EGKK", marginalWeather("EGKK"));

        // At 14:00 the TEMPO group is active, and it is below minima.
        AlternateCandidate duringTempo = screen(forecasts, List.of()).stream()
                .filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();
        assertThat(duringTempo.isUsable()).isFalse();

        // At 17:00 it has passed, and the prevailing conditions stand.
        AlternateCandidate afterTempo = selector.screen(DESTINATION,
                        AlternateCriteria.forJetWithPrecisionApproach(), forecasts, List.of(),
                        Instant.parse("2024-06-15T17:00:00Z")).stream()
                .filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();
        assertThat(afterTempo.isUsable()).isTrue();
    }

    @Test
    void rejects_an_aerodrome_with_no_forecast_covering_the_arrival() {
        AlternateCandidate egkk = screen(forecastsFor("EGLL", "EGSS"), List.of()).stream()
                .filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();

        assertThat(egkk.rejections()).contains("no forecast covering the arrival time");
    }

    @Test
    void accepts_an_aerodrome_with_no_forecast_when_the_criteria_do_not_demand_one() {
        AlternateCriteria relaxed = new AlternateCriteria(200.0, 6_000, 600, 3_000, true, false);

        List<AlternateCandidate> usable = selector.select(DESTINATION, relaxed,
                forecastsFor("EGLL"), List.of(), ARRIVAL);

        assertThat(usable).extracting(AlternateCandidate::icaoCode).contains("EGKK", "EGSS");
    }

    @Test
    void rejects_an_aerodrome_closed_by_a_NOTAM_at_the_arrival_time() {
        Notam closure = new NotamParser().parse("""
                A2000/24 NOTAMN
                Q) EGTT/QFALC/IV/NBO/A/000/999/5108N00011W005
                A) EGKK B) 2406151200 C) 2406151800
                E) AD CLOSED
                """);

        AlternateCandidate egkk = screen(forecastsFor("EGLL", "EGKK", "EGSS"), List.of(closure))
                .stream().filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();

        assertThat(egkk.isUsable()).isFalse();
        assertThat(egkk.rejections()).contains("closed by A2000/24");
    }

    @Test
    void a_closure_that_has_ended_by_the_arrival_time_does_not_rule_it_out() {
        Notam earlier = new NotamParser().parse("""
                A2100/24 NOTAMN
                Q) EGTT/QFALC/IV/NBO/A/000/999/5108N00011W005
                A) EGKK B) 2406150600 C) 2406150900
                E) AD CLOSED
                """);

        AlternateCandidate egkk = screen(forecastsFor("EGLL", "EGKK", "EGSS"), List.of(earlier))
                .stream().filter(c -> c.icaoCode().equals("EGKK")).findFirst().orElseThrow();

        assertThat(egkk.isUsable()).isTrue();
    }

    @Test
    void collects_every_reason_an_aerodrome_cannot_be_used_rather_than_the_first() {
        Map<String, TafReport> forecasts = forecastsFor("EGLL");
        forecasts.put("EGKB", badWeather("EGKB"));

        AlternateCandidate egkb = screen(forecasts, List.of()).stream()
                .filter(c -> c.icaoCode().equals("EGKB")).findFirst().orElseThrow();

        assertThat(egkb.rejections()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void the_screening_keeps_the_rejected_aerodromes_after_the_usable_ones() {
        List<AlternateCandidate> screened = screen(forecastsFor("EGLL", "EGKK"), List.of());

        int firstRejected = 0;
        while (firstRejected < screened.size() && screened.get(firstRejected).isUsable()) {
            firstRejected++;
        }
        assertThat(screened.subList(firstRejected, screened.size()))
                .allSatisfy(candidate -> assertThat(candidate.isUsable()).isFalse());
    }

    @Test
    void the_search_radius_bounds_what_is_considered() {
        List<AlternateCandidate> near = selector.screen(DESTINATION,
                AlternateCriteria.forJetWithPrecisionApproach().withinNm(10.0),
                forecastsFor("EGLL", "EGKK", "EGSS"), List.of(), ARRIVAL);

        assertThat(near).extracting(AlternateCandidate::icaoCode).containsExactly("EGLL");
    }

    @Test
    void the_best_alternate_is_the_nearest_usable_one() {
        assertThat(selector.best(DESTINATION, AlternateCriteria.forJetWithPrecisionApproach(),
                forecastsFor("EGLL", "EGKK", "EGSS"), List.of(), ARRIVAL))
                .map(AlternateCandidate::icaoCode)
                .contains("EGLL");
    }

    @Test
    void reports_no_alternate_when_nothing_qualifies() {
        assertThat(selector.best(GeoPoint.of(0.0, 0.0),
                AlternateCriteria.forJetWithPrecisionApproach(),
                Map.of(), List.of(), ARRIVAL))
                .isEmpty();
    }

    @Test
    void a_candidate_summarises_itself_for_the_OFP() {
        AlternateCandidate egll = screen(forecastsFor("EGLL"), List.of()).get(0);

        assertThat(egll.summary()).contains("EGLL").contains("usable");
    }

    private List<AlternateCandidate> screen(Map<String, TafReport> forecasts, List<Notam> notams) {
        return selector.screen(DESTINATION, AlternateCriteria.forJetWithPrecisionApproach(),
                forecasts, notams, ARRIVAL);
    }

    private AlternateCandidate screenFor(String icao) {
        return screen(forecastsFor("EGLL", "EGKK", "EGSS", "EGKB", "EGMD"), List.of()).stream()
                .filter(c -> c.icaoCode().equals(icao))
                .findFirst()
                .orElseThrow();
    }
}
