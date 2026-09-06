package io.aerodyne.navdata.arinc;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layout check, exercised by feeding the parser a file written to a <em>different</em>
 * column map and confirming that it notices.
 *
 * <p>That is the whole point of the class: a wrong layout produces plausible-looking wrong data
 * rather than an error, so the only way to know the check works is to make one and see it caught.
 */
class Arinc424DiagnosticTest {

    private final Arinc424Parser parser = new Arinc424Parser();

    /**
     * A VHF navaid record with the position at the column the layout expects.
     *
     * @param positionColumn where to put the 19-character position field, 1-indexed
     */
    private static String navaidRecord(String ident, int positionColumn) {
        char[] line = new char[132];
        java.util.Arrays.fill(line, ' ');
        place(line, 1, "S");
        place(line, 2, "EUR");
        place(line, 5, "D");
        place(line, 11, "EG");
        place(line, 14, ident);
        place(line, 20, "EG");
        place(line, 23, "11530");
        place(line, 28, "VDHW");
        place(line, positionColumn, "N51180500W000265000");
        place(line, 94, "OCKHAM");
        place(line, 129, "2413");
        return new String(line);
    }

    private static void place(char[] line, int column, String text) {
        for (int i = 0; i < text.length() && column - 1 + i < line.length; i++) {
            line[column - 1 + i] = text.charAt(i);
        }
    }

    private static String file(int positionColumn, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> navaidRecord(String.format("N%03d", i), positionColumn))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void a_file_that_fits_the_layout_reads_clean() {
        Arinc424File parsed = parser.parse(new StringReader(
                file(Arinc424Layout.VHF_POSITION_START, 50)));

        Arinc424Diagnostic.Report report = Arinc424Diagnostic.check(parsed);

        assertThat(parsed.navaids()).hasSize(50);
        assertThat(report.layoutLooksRight()).isTrue();
        assertThat(report.suspectTypes()).isEmpty();
        assertThat(report.types()).singleElement()
                .satisfies(type -> {
                    assertThat(type.type()).isEqualTo(ArincRecordType.VHF_NAVAID);
                    assertThat(type.read()).isEqualTo(50);
                    assertThat(type.failed()).isZero();
                    assertThat(type.verdict()).isEqualTo(Arinc424Diagnostic.Verdict.GOOD);
                });
    }

    @Test
    void a_file_written_to_a_different_column_map_is_caught() {
        // The position four columns to the left of where the layout looks for it: exactly the
        // kind of supplier variant that would otherwise pass unnoticed.
        Arinc424File parsed = parser.parse(new StringReader(
                file(Arinc424Layout.VHF_POSITION_START - 4, 50)));

        Arinc424Diagnostic.Report report = Arinc424Diagnostic.check(parsed);

        assertThat(report.layoutLooksRight()).isFalse();
        assertThat(report.suspectTypes()).containsExactly(ArincRecordType.VHF_NAVAID);
        assertThat(report.describe()).contains("PROBABLE LAYOUT MISMATCH");
    }

    @Test
    void the_report_gives_the_failure_rate_that_led_to_the_verdict() {
        Arinc424File parsed = parser.parse(new StringReader(
                file(Arinc424Layout.VHF_POSITION_START - 4, 50)));

        Arinc424Diagnostic.TypeReport navaids = Arinc424Diagnostic.check(parsed).types().get(0);

        assertThat(navaids.failureRate()).isEqualTo(1.0);
        assertThat(navaids.describe()).contains("100.0%");
    }

    @Test
    void a_handful_of_bad_records_is_not_a_layout_problem() {
        // Forty-eight good records and two truncated ones: a normal file.
        String mixed = file(Arinc424Layout.VHF_POSITION_START, 48) + "\nSEURD BAD\nSEURD ALSOBAD";

        Arinc424Diagnostic.Report report =
                Arinc424Diagnostic.check(parser.parse(new StringReader(mixed)));

        assertThat(report.layoutLooksRight()).isTrue();
        assertThat(report.types().get(0).verdict())
                .isEqualTo(Arinc424Diagnostic.Verdict.SOME_BAD_RECORDS);
        assertThat(report.types().get(0).failureRate()).isCloseTo(0.04, within());
    }

    @Test
    void too_few_records_of_a_type_to_judge_says_so_rather_than_guessing() {
        Arinc424File parsed = parser.parse(new StringReader(
                file(Arinc424Layout.VHF_POSITION_START - 4, 3)));

        Arinc424Diagnostic.Report report = Arinc424Diagnostic.check(parsed);

        assertThat(report.types().get(0).verdict())
                .isEqualTo(Arinc424Diagnostic.Verdict.TOO_FEW_TO_JUDGE);
        assertThat(report.layoutLooksRight()).isTrue();
    }

    @Test
    void reports_the_real_extract_as_fitting_the_layout() throws Exception {
        try (var stream = getClass().getResourceAsStream("/arinc/extract.arinc");
             var reader = new java.io.InputStreamReader(stream,
                     java.nio.charset.StandardCharsets.ISO_8859_1)) {
            Arinc424Diagnostic.Report report =
                    Arinc424Diagnostic.check(parser.parse(reader));

            // The extract deliberately holds a few malformed records, but too few of any one
            // type to be called a mismatch.
            assertThat(report.layoutLooksRight()).isTrue();
            assertThat(report.describe()).contains("the column map fits this file");
        }
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.001);
    }
}
