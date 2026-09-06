package io.aerodyne.api.service;

import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.metar.MetarParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherServiceTest {

    private final WeatherService weather = new WeatherService();

    @Test
    void plans_against_the_standard_atmosphere_until_a_forecast_is_loaded() {
        assertThat(weather.upperAirForecast()).isEmpty();
        assertThat(weather.forDeparture(Instant.now()))
                .isInstanceOf(ConstantWeatherModel.class)
                .extracting(model -> model.source())
                .isEqualTo("ISA, no wind");
    }

    @Test
    void accepts_a_METAR_and_indexes_it_by_aerodrome() {
        weather.submitMetar("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021");

        assertThat(weather.observationFor("EGLL")).isPresent();
        assertThat(weather.observations()).containsOnlyKeys("EGLL");
    }

    @Test
    void a_newer_observation_replaces_an_older_one() {
        weather.submitMetar("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021");
        weather.submitMetar("EGLL 151220Z 27018KT 9999 SCT030 19/10 Q1020");

        assertThat(weather.observationFor("EGLL"))
                .get()
                .extracting(report -> report.conditions().wind().speedKt())
                .isEqualTo(18.0);
    }

    @Test
    void an_older_observation_arriving_late_does_not_displace_the_newer_one() {
        weather.submitMetar("EGLL 151220Z 27018KT 9999 SCT030 19/10 Q1020");
        weather.submitMetar("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021");

        assertThat(weather.observationFor("EGLL"))
                .get()
                .extracting(report -> report.conditions().wind().speedKt())
                .isEqualTo(18.0);
    }

    @Test
    void accepts_a_TAF_and_answers_what_it_forecasts_at_a_moment() {
        weather.submitTaf("TAF EGLL 151100Z 1512/1618 25012KT 9999 SCT035 "
                + "TEMPO 1514/1518 3000 BR BKN006");

        assertThat(weather.forecastFor("EGLL")).isPresent();
        assertThat(weather.forecasts()).containsOnlyKeys("EGLL");
    }

    @Test
    void accepts_a_NOTAM() {
        weather.submitNotam("""
                A1234/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) RWY 09L CLOSED
                """);

        assertThat(weather.notams()).hasSize(1);
        assertThat(weather.notams().get(0).closesRunway()).isTrue();
    }

    @Test
    void a_replacement_NOTAM_supersedes_the_one_it_names() {
        weather.submitNotam("""
                A1234/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) RWY 09L CLOSED
                """);
        weather.submitNotam("""
                A1300/24 NOTAMR A1234/24
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406151200 C) 2406152000
                E) RWY 09L CLOSED, REVISED TIMES
                """);

        assertThat(weather.notams()).extracting(notam -> notam.identifier())
                .containsExactly("A1300/24");
    }

    @Test
    void a_cancellation_removes_the_NOTAM_and_adds_nothing() {
        weather.submitNotam("""
                A1234/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) RWY 09L CLOSED
                """);
        weather.submitNotam("""
                A1400/24 NOTAMC A1234/24
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406151200
                E) CANCELLED
                """);

        assertThat(weather.notams()).isEmpty();
    }

    @Test
    void pruning_drops_stale_observations_and_expired_NOTAMs() {
        weather.submitMetar("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021");
        weather.submitNotam("""
                A1234/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) RWY 09L CLOSED
                """);

        int dropped = weather.prune(Instant.parse("2024-06-16T00:00:00Z"));

        assertThat(dropped).isEqualTo(2);
        assertThat(weather.observations()).isEmpty();
        assertThat(weather.notams()).isEmpty();
    }

    @Test
    void pruning_keeps_a_permanent_NOTAM() {
        weather.submitNotam("""
                A5000/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) PERM
                E) RWY 05/23 PERMANENTLY CLOSED
                """);

        weather.prune(Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(weather.notams()).hasSize(1);
    }

    @Test
    void refuses_something_that_is_not_a_METAR() {
        assertThatThrownBy(() -> weather.submitMetar("the weather is fine"))
                .isInstanceOf(MetarParser.MetarParseException.class);
    }

    @Test
    void what_it_returns_is_a_snapshot_that_a_later_submission_does_not_change() {
        weather.submitMetar("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021");
        var snapshot = weather.observations();

        weather.submitMetar("EGKK 151150Z 25012KT 9999 FEW035 18/11 Q1021");

        assertThat(snapshot).hasSize(1);
        assertThat(weather.observations()).hasSize(2);
    }
}
