package io.aerodyne.weather.metar;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TafParserTest {

    private final TafParser parser = new TafParser(Instant.parse("2024-06-15T12:00:00Z"));

    @Test
    void reads_the_header_and_validity_period() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035");

        assertThat(taf.stationIcao()).isEqualTo("EGLL");
        assertThat(taf.issuedAt()).isEqualTo(Instant.parse("2024-06-15T11:00:00Z"));
        assertThat(taf.validFrom()).isEqualTo(Instant.parse("2024-06-15T12:00:00Z"));
        assertThat(taf.validTo()).isEqualTo(Instant.parse("2024-06-16T18:00:00Z"));
        assertThat(taf.covers(Instant.parse("2024-06-16T00:00:00Z"))).isTrue();
        assertThat(taf.covers(Instant.parse("2024-06-16T18:00:00Z"))).isFalse();
    }

    @Test
    void reads_the_body_as_the_conditions_from_the_start_of_the_period() {
        TafReport taf = parser.parse("TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035");

        SurfaceConditions body =
                taf.prevailingAt(Instant.parse("2024-06-15T13:00:00Z")).orElseThrow();

        assertThat(body.wind().directionDeg()).isEqualTo(250.0);
        assertThat(body.visibility()).hasValue(10_000);
        assertThat(body.ceilingFt()).isEmpty();
    }

    @Test
    void a_FM_group_replaces_the_prevailing_forecast_from_its_time() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                        + "FM151800 20018KT 6000 -RA BKN012");

        assertThat(taf.prevailingAt(Instant.parse("2024-06-15T17:59:00Z")).orElseThrow()
                .visibility()).hasValue(10_000);
        SurfaceConditions after =
                taf.prevailingAt(Instant.parse("2024-06-15T18:00:00Z")).orElseThrow();
        assertThat(after.visibility()).hasValue(6000);
        assertThat(after.ceilingFt()).hasValue(1200);
        assertThat(after.wind().speedKt()).isEqualTo(18.0);
    }

    @Test
    void a_BECMG_group_replaces_the_prevailing_forecast_too() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT CAVOK "
                        + "BECMG 1520/1522 18020G32KT 3000 RA BKN008");

        assertThat(taf.prevailingAt(Instant.parse("2024-06-15T19:00:00Z")).orElseThrow()
                .cavok()).isTrue();
        SurfaceConditions after =
                taf.prevailingAt(Instant.parse("2024-06-15T21:00:00Z")).orElseThrow();
        assertThat(after.ceilingFt()).hasValue(800);
        assertThat(after.gust()).contains(32);
    }

    @Test
    void a_TEMPO_group_does_not_change_what_is_prevailing() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                        + "TEMPO 1514/1518 4000 SHRA BKN010");

        Instant during = Instant.parse("2024-06-15T15:00:00Z");

        assertThat(taf.prevailingAt(during).orElseThrow().visibility()).hasValue(10_000);
        assertThat(taf.worstCaseAt(during).orElseThrow().visibility()).hasValue(4000);
        assertThat(taf.worstCaseAt(during).orElseThrow().ceilingFt()).hasValue(1000);
    }

    @Test
    void a_TEMPO_group_stops_applying_at_the_end_of_its_period() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                        + "TEMPO 1514/1518 4000 SHRA BKN010");

        assertThat(taf.worstCaseAt(Instant.parse("2024-06-15T18:30:00Z")).orElseThrow()
                .visibility()).hasValue(10_000);
    }

    @Test
    void a_TEMPO_group_that_mentions_only_the_visibility_leaves_the_cloud_alone() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 BKN020 "
                        + "TEMPO 1514/1518 3000 BR");

        SurfaceConditions worst =
                taf.worstCaseAt(Instant.parse("2024-06-15T15:00:00Z")).orElseThrow();

        assertThat(worst.visibility()).hasValue(3000);
        assertThat(worst.ceilingFt()).hasValue(2000);
        assertThat(worst.presentWeather()).contains("BR");
    }

    @Test
    void reads_a_PROB_group_and_treats_it_as_a_fluctuation() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                        + "PROB30 TEMPO 1518/1522 1200 FG BKN002");

        Instant during = Instant.parse("2024-06-15T19:00:00Z");

        assertThat(taf.groupsActiveAt(during))
                .anyMatch(g -> g.changeType() == TafChangeType.PROBABILITY
                        && g.probability() == 30);
        assertThat(taf.prevailingAt(during).orElseThrow().visibility()).hasValue(10_000);
        assertThat(taf.worstCaseAt(during).orElseThrow().visibility()).hasValue(1200);
    }

    @Test
    void the_worst_case_keeps_the_stronger_wind() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                        + "TEMPO 1514/1518 25025G40KT 9999");

        SurfaceConditions worst =
                taf.worstCaseAt(Instant.parse("2024-06-15T15:00:00Z")).orElseThrow();

        assertThat(worst.peakWindKt()).isEqualTo(40.0);
    }

    @Test
    void an_hour_of_24_is_midnight_ending_that_day() {
        TafReport taf = parser.parse("TAF EGLL 151100Z 1512/1624 25012KT 9999 SCT035");

        assertThat(taf.validTo()).isEqualTo(Instant.parse("2024-06-17T00:00:00Z"));
    }

    @Test
    void a_validity_period_running_into_the_next_month_still_resolves_forward() {
        TafParser endOfMonth = new TafParser(Instant.parse("2024-06-30T12:00:00Z"));

        TafReport taf = endOfMonth.parse("TAF EGLL 301100Z 3012/0112 25012KT 9999 SCT035");

        assertThat(taf.validFrom()).isEqualTo(Instant.parse("2024-06-30T12:00:00Z"));
        assertThat(taf.validTo()).isEqualTo(Instant.parse("2024-07-01T12:00:00Z"));
    }

    @Test
    void reads_the_amendment_and_correction_flags() {
        TafReport taf = parser.parse("TAF AMD EGLL 151100Z 1512/1618 25012KT 9999 SCT035");

        assertThat(taf.amended()).isTrue();
        assertThat(taf.corrected()).isFalse();
    }

    @Test
    void a_cancelled_forecast_forecasts_nothing() {
        TafReport taf = parser.parse("TAF AMD EGLL 151100Z 1512/1618 CNL");

        assertThat(taf.cancelled()).isTrue();
        assertThat(taf.covers(Instant.parse("2024-06-15T13:00:00Z"))).isFalse();
        assertThat(taf.prevailingAt(Instant.parse("2024-06-15T13:00:00Z"))).isEmpty();
    }

    @Test
    void reports_the_lowest_ceiling_forecast_anywhere_in_the_period() {
        TafReport taf = parser.parse(
                "TAF EGLL 151100Z 1512/1618 25012KT 9999 BKN020 "
                        + "TEMPO 1514/1518 4000 SHRA BKN010 "
                        + "PROB30 TEMPO 1518/1522 1200 FG BKN002");

        assertThat(taf.lowestForecastCeilingFt()).hasValue(200);
    }

    @Test
    void a_time_outside_the_validity_period_forecasts_nothing() {
        TafReport taf = parser.parse("TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035");

        assertThat(taf.prevailingAt(Instant.parse("2024-06-15T11:00:00Z"))).isEmpty();
        assertThat(taf.worstCaseAt(Instant.parse("2024-06-17T00:00:00Z"))).isEmpty();
    }

    @Test
    void refuses_a_forecast_with_no_validity_period() {
        assertThatThrownBy(() -> parser.parse("TAF EGLL 151100Z 25012KT 9999 SCT035"))
                .isInstanceOf(MetarParser.MetarParseException.class)
                .hasMessageContaining("validity period");
    }

    @Test
    void refuses_a_forecast_with_no_station() {
        assertThatThrownBy(() -> parser.parse("TAF 151100Z 1512/1618 25012KT"))
                .isInstanceOf(MetarParser.MetarParseException.class)
                .hasMessageContaining("station");
    }

    @Test
    void keeps_the_forecast_as_received() {
        String raw = "TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035=";

        assertThat(parser.parse(raw).raw()).isEqualTo(raw);
    }
}
