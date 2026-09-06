package io.aerodyne.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CoordinateFormatTest {

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @CsvSource({
            // packed ARINC 424: hemisphere, ddmmssss / dddmmssss
            "N51285900W000002740, 51.4830556, -0.0076111",
            "S23261980W046282900, -23.4388333, -46.4747222",
            // degrees and decimal minutes
            "N5130.0W00007.5,     51.5,        -0.125",
            "S3356.8E15110.6,     -33.9466667, 151.1766667",
            // degrees, minutes, seconds with trailing hemisphere
            "513000N0000730W,     51.5,        -0.125",
            "513000N 0000730W,    51.5,        -0.125",
            // ICAO flight-plan forms
            "5130N00007W,         51.5,        -0.1166667",
            "51N007W,             51.0,        -7.0",
            // oceanic shorthand, all four quadrants
            "5130N,               51.0,        -30.0",
            "5130E,               51.0,        30.0",
            "5130S,               -51.0,       30.0",
            "5130W,               -51.0,       -30.0",
            // signed decimal
            "51.5/-0.125,         51.5,        -0.125",
            "-23.435556/-46.473056, -23.435556, -46.473056",
    })
    void parses_every_supported_notation(String text, double latitude, double longitude) {
        GeoPoint p = CoordinateFormat.parse(text);

        assertThat(p.latitude()).isCloseTo(latitude, within(1e-6));
        assertThat(p.longitude()).isCloseTo(longitude, within(1e-6));
    }

    @Test
    void accepts_lower_case_and_surrounding_whitespace() {
        assertThat(CoordinateFormat.parse("  n5130.0w00007.5  "))
                .isEqualTo(CoordinateFormat.parse("N5130.0W00007.5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"KOLIN", "UN862", "EGLL", "", "N5130.0", "5130X", "12345"})
    void returns_null_for_anything_that_is_not_a_coordinate(String token) {
        assertThat(CoordinateFormat.parseOrNull(token)).isNull();
    }

    @Test
    void returns_null_rather_than_throwing_for_a_null_token() {
        assertThat(CoordinateFormat.parseOrNull(null)).isNull();
    }

    @Test
    void parse_reports_the_offending_token() {
        assertThatThrownBy(() -> CoordinateFormat.parse("BEDEK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEDEK");
    }

    @Test
    void rejects_minutes_of_sixty_or_more() {
        assertThatThrownBy(() -> CoordinateFormat.parse("516000N0000730W"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void rejects_a_signed_decimal_latitude_beyond_the_pole() {
        assertThat(CoordinateFormat.parseOrNull("91.0/10.0")).isNull();
    }

    @Test
    void renders_degrees_and_decimal_minutes() {
        assertThat(CoordinateFormat.toArincDegreesMinutes(GeoPoint.of(51.5, -0.125)))
                .isEqualTo("N5130.0W00007.5");
        assertThat(CoordinateFormat.toArincDegreesMinutes(GeoPoint.of(-33.9466667, 151.1766667)))
                .isEqualTo("S3356.8E15110.6");
    }

    @Test
    void carries_into_the_degrees_when_the_minutes_round_up_to_sixty() {
        // 51.99999 degrees is 59.9994 minutes, which renders as 60.0 without the carry.
        assertThat(CoordinateFormat.toArincDegreesMinutes(GeoPoint.of(51.99999, 0.0)))
                .isEqualTo("N5200.0E00000.0");
    }

    @Test
    void renders_degrees_minutes_seconds() {
        assertThat(CoordinateFormat.toDegreesMinutesSeconds(GeoPoint.of(51.5, -0.125)))
                .isEqualTo("513000N 0000730W");
    }

    @Test
    void renders_oceanic_shorthand_only_for_whole_degrees() {
        assertThat(CoordinateFormat.toOceanicShorthandOrNull(GeoPoint.of(51.0, -30.0)))
                .isEqualTo("5130N");
        assertThat(CoordinateFormat.toOceanicShorthandOrNull(GeoPoint.of(-51.0, 30.0)))
                .isEqualTo("5130S");
        assertThat(CoordinateFormat.toOceanicShorthandOrNull(GeoPoint.of(51.5, -30.0))).isNull();
        assertThat(CoordinateFormat.toOceanicShorthandOrNull(GeoPoint.of(51.0, -120.0))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"51.5, -0.125", "-33.9, 151.2", "0.0, 0.0", "89.9, 179.9"})
    void round_trips_through_degrees_minutes_seconds(double latitude, double longitude) {
        GeoPoint original = GeoPoint.of(latitude, longitude);

        GeoPoint back = CoordinateFormat.parse(CoordinateFormat.toDegreesMinutesSeconds(original));

        // One second of arc is about 30 m, the resolution the notation carries.
        assertThat(Geodesy.distanceNm(original, back)).isLessThan(0.02);
    }

    @Test
    void round_trips_through_signed_decimal_without_loss() {
        GeoPoint original = GeoPoint.of(-23.435556, -46.473056);

        assertThat(CoordinateFormat.parse(CoordinateFormat.toSignedDecimal(original)))
                .isEqualTo(original);
    }

    @Test
    void toString_uses_the_OFP_notation() {
        assertThat(GeoPoint.of(51.5, -0.125)).hasToString("N5130.0W00007.5");
    }
}
