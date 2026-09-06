package io.aerodyne.dispatch.notam;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class NotamParserTest {

    private final NotamParser parser = new NotamParser();

    private static final String RUNWAY_CLOSED = """
            A1234/24 NOTAMN
            Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
            A) EGLL
            B) 2406150600
            C) 2406151800
            E) RWY 09L/27R CLOSED DUE WIP
            """;

    @Test
    void reads_the_header() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.identifier()).isEqualTo("A1234/24");
        assertThat(notam.type()).isEqualTo(Notam.NotamType.NEW);
        assertThat(notam.replacesOrCancels()).isNull();
    }

    @Test
    void reads_the_Q_line() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.fir()).isEqualTo("EGTT");
        assertThat(notam.qCode()).hasToString("QMRLC");
        assertThat(notam.trafficType()).isEqualTo("IV");
        assertThat(notam.purpose()).isEqualTo("NBO");
        assertThat(notam.scope()).isEqualTo("A");
    }

    @Test
    void reads_the_affected_area_from_the_Q_line() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.affectedCentre()).isPresent();
        assertThat(Geodesy.distanceNm(notam.affectedCentre().orElseThrow(),
                GeoPoint.of(51.4833, -0.4667))).isLessThan(1.0);
        assertThat(notam.radiusNm()).isEqualTo(5.0);
        assertThat(notam.appliesAt(GeoPoint.of(51.48, -0.46))).isTrue();
        assertThat(notam.appliesAt(GeoPoint.of(52.5, -0.46))).isFalse();
    }

    @Test
    void reads_the_vertical_band_with_999_meaning_no_upper_limit() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.lowerLimit()).hasValue(0);
        assertThat(notam.upperLimit()).isEmpty();
        assertThat(notam.affectsAltitude(35_000)).isTrue();
    }

    @Test
    void reads_a_bounded_vertical_band() {
        Notam notam = parser.parse("""
                A9000/24 NOTAMN
                Q) EGTT/QRTCA/IV/BO/W/100/250/5200N00100W020
                A) EGTT B) 2406150600 C) 2406151800
                E) TEMPORARY RESTRICTED AREA
                """);

        assertThat(notam.lowerLimit()).hasValue(10_000);
        assertThat(notam.upperLimit()).hasValue(25_000);
        assertThat(notam.affectsAltitude(15_000)).isTrue();
        assertThat(notam.affectsAltitude(35_000)).isFalse();
    }

    @Test
    void reads_the_location_and_the_validity_period() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.locations()).containsExactly("EGLL");
        assertThat(notam.appliesTo("EGLL")).isTrue();
        assertThat(notam.appliesTo("egll")).isTrue();
        assertThat(notam.appliesTo("EGKK")).isFalse();
        assertThat(notam.validFrom()).isEqualTo(Instant.parse("2024-06-15T06:00:00Z"));
        assertThat(notam.validTo()).isEqualTo(Instant.parse("2024-06-15T18:00:00Z"));
    }

    @Test
    void is_active_only_inside_its_validity_period() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.isActiveAt(Instant.parse("2024-06-15T05:59:00Z"))).isFalse();
        assertThat(notam.isActiveAt(Instant.parse("2024-06-15T12:00:00Z"))).isTrue();
        assertThat(notam.isActiveAt(Instant.parse("2024-06-15T18:00:00Z"))).isFalse();
    }

    @Test
    void reads_the_free_text() {
        assertThat(parser.parse(RUNWAY_CLOSED).text()).isEqualTo("RWY 09L/27R CLOSED DUE WIP");
    }

    @Test
    void decodes_what_the_NOTAM_is_about_from_its_Q_code() {
        Notam notam = parser.parse(RUNWAY_CLOSED);

        assertThat(notam.closesRunway()).isTrue();
        assertThat(notam.closesAerodrome()).isFalse();
        assertThat(notam.isOperationallySignificant()).isTrue();
        assertThat(notam.qCode().describe()).isEqualTo("runway unavailable");
    }

    @Test
    void recognises_an_aerodrome_closure() {
        Notam notam = parser.parse("""
                A2000/24 NOTAMN
                Q) EGTT/QFALC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) AD CLOSED
                """);

        assertThat(notam.closesAerodrome()).isTrue();
        assertThat(notam.closesRunway()).isFalse();
    }

    @Test
    void recognises_an_approach_aid_withdrawn() {
        Notam notam = parser.parse("""
                A3000/24 NOTAMN
                Q) EGTT/QICAS/I/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) ILS RWY 27L U/S
                """);

        assertThat(notam.withdrawsApproachAid()).isTrue();
        assertThat(notam.isOperationallySignificant()).isTrue();
    }

    @Test
    void an_informational_NOTAM_is_not_operationally_significant() {
        Notam notam = parser.parse("""
                A4000/24 NOTAMN
                Q) EGTT/QFAXX/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800
                E) NEW TAXIWAY DESIGNATOR IN USE
                """);

        assertThat(notam.isOperationallySignificant()).isFalse();
        assertThat(notam.qCode().isInformational()).isTrue();
    }

    @Test
    void reads_a_replacement_and_the_NOTAM_it_supersedes() {
        Notam notam = parser.parse("""
                A1300/24 NOTAMR A1234/24
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406151200 C) 2406152000
                E) RWY 09L/27R CLOSED, REVISED TIMES
                """);

        assertThat(notam.type()).isEqualTo(Notam.NotamType.REPLACEMENT);
        assertThat(notam.replacesOrCancels()).isEqualTo("A1234/24");
    }

    @Test
    void a_cancellation_is_never_active() {
        Notam notam = parser.parse("""
                A1400/24 NOTAMC A1234/24
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406151200
                E) CANCELLED
                """);

        assertThat(notam.type()).isEqualTo(Notam.NotamType.CANCELLATION);
        assertThat(notam.isActiveAt(Instant.parse("2024-06-15T13:00:00Z"))).isFalse();
    }

    @Test
    void a_permanent_NOTAM_has_no_end() {
        Notam notam = parser.parse("""
                A5000/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) PERM
                E) RWY 05/23 PERMANENTLY CLOSED
                """);

        assertThat(notam.isPermanent()).isTrue();
        assertThat(notam.validTo()).isNull();
        assertThat(notam.isActiveAt(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void notes_an_estimated_end_time() {
        Notam notam = parser.parse("""
                A6000/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406151800EST
                E) RWY CLOSED
                """);

        assertThat(notam.estimatedEnd()).isTrue();
        assertThat(notam.validTo()).isEqualTo(Instant.parse("2024-06-15T18:00:00Z"));
    }

    @Test
    void reads_fields_that_run_together_on_one_line() {
        Notam notam = parser.parse(
                "A7000/24 NOTAMN Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005 "
                        + "A) EGLL B) 2406150600 C) 2406151800 E) RWY 09L CLOSED");

        assertThat(notam.locations()).containsExactly("EGLL");
        assertThat(notam.text()).isEqualTo("RWY 09L CLOSED");
    }

    @Test
    void reads_several_locations() {
        Notam notam = parser.parse("""
                A8000/24 NOTAMN
                Q) EGTT/QAFXX/IV/NBO/E/000/999/5129N00028W050
                A) EGLL EGKK EGSS B) 2406150600 C) 2406151800
                E) SEE TEXT
                """);

        assertThat(notam.locations()).containsExactly("EGLL", "EGKK", "EGSS");
    }

    @Test
    void reads_the_schedule_line_verbatim() {
        Notam notam = parser.parse("""
                A8100/24 NOTAMN
                Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
                A) EGLL B) 2406150600 C) 2406201800
                D) DAILY 2200-0500
                E) RWY 09L CLOSED
                """);

        assertThat(notam.schedule()).isEqualTo("DAILY 2200-0500");
    }

    @Test
    void survives_a_NOTAM_with_no_readable_Q_line() {
        Notam notam = parser.parse("""
                A8200/24 NOTAMN
                Q) NONSENSE
                A) EGLL B) 2406150600 C) 2406151800
                E) SOMETHING HAPPENED
                """);

        assertThat(notam.qCode()).isEqualTo(QCode.UNKNOWN);
        assertThat(notam.isOperationallySignificant()).isFalse();
        assertThat(notam.text()).isEqualTo("SOMETHING HAPPENED");
    }

    @Test
    void refuses_a_NOTAM_with_no_identifier() {
        assertThatThrownBy(() -> parser.parse("Q) EGTT/QMRLC/IV/NBO/A/000/999 A) EGLL"))
                .isInstanceOf(NotamParser.NotamParseException.class)
                .hasMessageContaining("identifier");
    }

    @Test
    void skips_the_unreadable_ones_in_a_bulletin() {
        List<Notam> notams = parser.parseAll(List.of(
                RUNWAY_CLOSED, "this is not a NOTAM", RUNWAY_CLOSED.replace("A1234", "A1235")));

        assertThat(notams).hasSize(2);
        assertThat(notams).extracting(Notam::identifier)
                .containsExactly("A1234/24", "A1235/24");
    }

    @Test
    void a_Q_code_that_is_not_five_letters_is_unknown_rather_than_an_error() {
        assertThat(QCode.parse("QMR")).isEqualTo(QCode.UNKNOWN);
        assertThat(QCode.parse(null)).isEqualTo(QCode.UNKNOWN);
        assertThat(QCode.parse("XMRLC")).isEqualTo(QCode.UNKNOWN);
    }

    @Test
    void summarises_a_NOTAM_for_a_briefing() {
        assertThat(parser.parse(RUNWAY_CLOSED).summary())
                .contains("A1234/24")
                .contains("QMRLC")
                .contains("EGLL")
                .contains("RWY 09L/27R CLOSED");
    }
}
