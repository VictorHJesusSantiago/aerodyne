package io.aerodyne.weather.metar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MetarParserTest {

    /** Every report in these tests is read as if it were the 15th of June 2024. */
    private final MetarParser parser =
            new MetarParser(Instant.parse("2024-06-15T12:00:00Z"));

    @Test
    void decodes_an_ordinary_report() {
        MetarReport metar = parser.parse(
                "EGLL 151150Z 25012KT 9999 FEW035 SCT120 18/11 Q1021 NOSIG");

        assertThat(metar.stationIcao()).isEqualTo("EGLL");
        assertThat(metar.observedAt()).isEqualTo(Instant.parse("2024-06-15T11:50:00Z"));
        assertThat(metar.conditions().wind().directionDeg()).isEqualTo(250.0);
        assertThat(metar.conditions().wind().speedKt()).isEqualTo(12.0);
        assertThat(metar.conditions().visibility()).hasValue(10_000);
        assertThat(metar.temperatureC()).isEqualTo(18);
        assertThat(metar.dewPointC()).isEqualTo(11);
        assertThat(metar.qnh()).contains(1021.0);
        assertThat(metar.trend()).isEqualTo("NOSIG");
    }

    @Nested
    @DisplayName("wind")
    class Wind {

        @Test
        void reads_a_gust() {
            MetarReport metar = parser.parse("EGLL 151150Z 25012G28KT 9999 NSC 18/11 Q1021");

            assertThat(metar.conditions().gust()).contains(28);
            assertThat(metar.conditions().peakWindKt()).isEqualTo(28.0);
        }

        @Test
        void reads_calm_as_no_wind() {
            MetarReport metar = parser.parse("EGLL 151150Z 00000KT 9999 NSC 18/11 Q1021");

            assertThat(metar.conditions().wind()).isEqualTo(io.aerodyne.weather.WindVector.CALM);
        }

        @Test
        void reads_a_variable_direction_as_a_speed_without_a_bearing() {
            MetarReport metar = parser.parse("EGLL 151150Z VRB03KT 9999 NSC 18/11 Q1021");

            assertThat(metar.conditions().variableWind()).isTrue();
            assertThat(metar.conditions().wind().speedKt()).isEqualTo(3.0);
        }

        @Test
        void reads_a_reported_direction_range() {
            MetarReport metar = parser.parse("EGLL 151150Z 25012KT 220V280 9999 NSC 18/11 Q1021");

            assertThat(metar.conditions().windVariesFromDeg()).isEqualTo(220);
            assertThat(metar.conditions().windVariesToDeg()).isEqualTo(280);
        }

        @Test
        void converts_a_wind_reported_in_metres_per_second() {
            MetarReport metar = parser.parse("UUEE 151150Z 25010MPS 9999 NSC 18/11 Q1021");

            assertThat(metar.conditions().wind().speedKt()).isCloseTo(19.4, within(0.1));
        }
    }

    @Nested
    @DisplayName("visibility and cloud")
    class VisibilityAndCloud {

        @Test
        void reads_nine_nine_nine_nine_as_ten_kilometres_or_more() {
            assertThat(parser.parse("EGLL 151150Z 25012KT 9999 NSC 18/11 Q1021")
                    .conditions().visibility()).hasValue(10_000);
        }

        @Test
        void reads_a_visibility_in_metres() {
            assertThat(parser.parse("EGLL 151150Z 25012KT 0800 FG OVC001 18/17 Q1021")
                    .conditions().visibility()).hasValue(800);
        }

        @Test
        void reads_a_visibility_in_statute_miles() {
            assertThat(parser.parse("KJFK 151151Z 25012KT 3SM BR BKN008 18/17 A2992")
                    .conditions().visibility()).hasValue(4828);
        }

        @Test
        void reads_a_fractional_statute_mile_visibility() {
            assertThat(parser.parse("KJFK 151151Z 25012KT 1/2SM FG VV002 18/17 A2992")
                    .conditions().visibility()).hasValue(805);
        }

        @Test
        void reads_a_mixed_number_of_statute_miles() {
            assertThat(parser.parse("KJFK 151151Z 25012KT 1 1/2SM BR BKN008 18/17 A2992")
                    .conditions().visibility()).hasValue(2414);
        }

        @Test
        void the_ceiling_is_the_lowest_broken_or_overcast_layer() {
            SurfaceConditions conditions = parser.parse(
                    "EGLL 151150Z 25012KT 9999 FEW008 SCT015 BKN022 OVC040 18/11 Q1021")
                    .conditions();

            assertThat(conditions.ceilingFt()).hasValue(2200);
            assertThat(conditions.lowestLayer()).get()
                    .extracting(CloudLayer::baseFtAgl).isEqualTo(800);
        }

        @Test
        void scattered_cloud_alone_is_not_a_ceiling() {
            assertThat(parser.parse("EGLL 151150Z 25012KT 9999 FEW008 SCT015 18/11 Q1021")
                    .conditions().ceilingFt()).isEmpty();
        }

        @Test
        void an_obscured_sky_reports_a_vertical_visibility_as_the_ceiling() {
            SurfaceConditions conditions =
                    parser.parse("EGLL 151150Z 25012KT 0200 FG VV002 18/18 Q1021").conditions();

            assertThat(conditions.ceilingFt()).hasValue(200);
        }

        @Test
        void reads_a_convective_layer() {
            SurfaceConditions conditions = parser.parse(
                    "EGLL 151150Z 25012KT 9999 TSRA BKN018CB 18/11 Q1021").conditions();

            assertThat(conditions.clouds()).anyMatch(CloudLayer::isConvective);
            assertThat(conditions.hasThunderstorm()).isTrue();
        }

        @Test
        void CAVOK_asserts_ten_kilometres_and_no_ceiling() {
            SurfaceConditions conditions =
                    parser.parse("EGLL 151150Z 25012KT CAVOK 18/11 Q1021").conditions();

            assertThat(conditions.cavok()).isTrue();
            assertThat(conditions.visibility()).hasValue(10_000);
            assertThat(conditions.ceilingFt()).isEmpty();
            assertThat(conditions.hasSignificantWeather()).isFalse();
        }
    }

    @Nested
    @DisplayName("pressure, weather and flags")
    class Rest {

        @Test
        void converts_an_american_altimeter_setting_to_hectopascals() {
            assertThat(parser.parse("KJFK 151151Z 25012KT 10SM SKC 18/11 A2992").qnh())
                    .get().satisfies(q -> assertThat(q).isCloseTo(1013.2, within(0.1)));
        }

        @Test
        void reads_negative_temperatures() {
            MetarReport metar = parser.parse("BIKF 151150Z 25012KT 9999 NSC M03/M07 Q1021");

            assertThat(metar.temperatureC()).isEqualTo(-3);
            assertThat(metar.dewPointC()).isEqualTo(-7);
            assertThat(metar.dewPointSpreadC()).hasValue(4.0);
        }

        @Test
        void reads_present_weather_groups() {
            SurfaceConditions conditions = parser.parse(
                    "EGLL 151150Z 25012KT 3000 -RA BR VCSH BKN008 18/17 Q1021").conditions();

            assertThat(conditions.presentWeather()).containsExactly("-RA", "BR", "VCSH");
            assertThat(conditions.hasSignificantWeather()).isTrue();
        }

        @Test
        void detects_freezing_precipitation() {
            assertThat(parser.parse("CYYZ 151150Z 25012KT 1200 FZRA OVC004 M02/M03 Q1000")
                    .conditions().hasFreezingPrecipitation()).isTrue();
        }

        @Test
        void reads_the_runway_visual_range() {
            MetarReport metar = parser.parse(
                    "EGLL 151150Z 25012KT 0300 R27L/0800V1200U R09R/0600 FG VV001 18/18 Q1021");

            assertThat(metar.runwayVisualRange()).hasSize(2);
            assertThat(metar.rvrFor("27L")).get()
                    .satisfies(rvr -> {
                        assertThat(rvr.minimumMetres()).isEqualTo(800);
                        assertThat(rvr.maximumMetres()).isEqualTo(1200);
                        assertThat(rvr.tendency()).isEqualTo("U");
                    });
            assertThat(metar.rvrFor("09R")).get()
                    .extracting(MetarReport.RunwayVisualRange::maximumMetres).isNull();
        }

        @Test
        void reads_the_report_type_and_flags() {
            MetarReport speci = parser.parse("SPECI EGLL 151150Z AUTO 25012KT 9999 NSC 18/11 Q1021");

            assertThat(speci.speci()).isTrue();
            assertThat(speci.automatic()).isTrue();
            assertThat(speci.corrected()).isFalse();
        }

        @Test
        void separates_the_remarks_from_the_body() {
            MetarReport metar = parser.parse(
                    "KJFK 151151Z 25012KT 10SM SKC 18/11 A2992 RMK AO2 SLP134 T01780111");

            assertThat(metar.remarks()).isEqualTo("AO2 SLP134 T01780111");
            assertThat(metar.qnh()).isPresent();
        }

        @Test
        void keeps_the_report_as_received() {
            String raw = "EGLL 151150Z 25012KT 9999 NSC 18/11 Q1021=";

            assertThat(parser.parse(raw).raw()).isEqualTo(raw);
        }
    }

    @Nested
    @DisplayName("time and validity")
    class Timing {

        @Test
        void a_report_timed_at_the_end_of_the_month_belongs_to_the_previous_one() {
            MetarParser firstOfJuly =
                    new MetarParser(Instant.parse("2024-07-01T00:30:00Z"));

            assertThat(firstOfJuly.parse("EGLL 302350Z 25012KT 9999 NSC 18/11 Q1021").observedAt())
                    .isEqualTo(Instant.parse("2024-06-30T23:50:00Z"));
        }

        @Test
        void an_observation_stays_current_for_ninety_minutes() {
            MetarReport metar = parser.parse("EGLL 151150Z 25012KT 9999 NSC 18/11 Q1021");

            assertThat(metar.isCurrentAt(Instant.parse("2024-06-15T12:00:00Z"))).isTrue();
            assertThat(metar.isCurrentAt(Instant.parse("2024-06-15T13:20:00Z"))).isTrue();
            assertThat(metar.isCurrentAt(Instant.parse("2024-06-15T13:21:00Z"))).isFalse();
        }
    }

    @Nested
    @DisplayName("minima")
    class Minima {

        @Test
        void conditions_at_or_above_the_minimum_meet_it() {
            SurfaceConditions conditions =
                    parser.parse("EGLL 151150Z 25012KT 3000 BKN008 18/11 Q1021").conditions();

            assertThat(conditions.meets(800, 3000)).isTrue();
            assertThat(conditions.meets(800, 3200)).isFalse();
            assertThat(conditions.meets(1000, 3000)).isFalse();
        }

        @Test
        void an_unreported_visibility_does_not_meet_a_minimum() {
            SurfaceConditions conditions =
                    parser.parse("EGLL 151150Z 25012KT NSC 18/11 Q1021").conditions();

            assertThat(conditions.visibility()).isEmpty();
            assertThat(conditions.meets(200, 800)).isFalse();
        }

        @Test
        void no_ceiling_reported_means_the_cloud_is_not_the_limit() {
            SurfaceConditions conditions =
                    parser.parse("EGLL 151150Z 25012KT 9999 FEW035 18/11 Q1021").conditions();

            assertThat(conditions.meets(3000, 5000)).isTrue();
        }
    }

    @Nested
    @DisplayName("malformed reports")
    class Malformed {

        @Test
        void refuses_a_report_with_no_station() {
            assertThatThrownBy(() -> parser.parse("151150Z 25012KT 9999 NSC"))
                    .isInstanceOf(MetarParser.MetarParseException.class)
                    .hasMessageContaining("station");
        }

        @Test
        void refuses_a_report_with_no_observation_time() {
            assertThatThrownBy(() -> parser.parse("EGLL 25012KT 9999 NSC"))
                    .isInstanceOf(MetarParser.MetarParseException.class)
                    .hasMessageContaining("observation time");
        }

        @Test
        void refuses_an_empty_report() {
            assertThatThrownBy(() -> parser.parse("   "))
                    .isInstanceOf(MetarParser.MetarParseException.class);
        }

        @Test
        void skips_a_national_group_it_does_not_recognise() {
            MetarReport metar = parser.parse(
                    "EGLL 151150Z 25012KT 9999 NSC 18/11 Q1021 WS R27L 88888");

            assertThat(metar.qnh()).contains(1021.0);
            assertThat(metar.conditions().visibility()).hasValue(10_000);
        }

        @Test
        void leaves_an_unmeasured_element_unreported_rather_than_guessing() {
            MetarReport metar = parser.parse("EGLL 151150Z /////KT //// NSC 18/11 Q////");

            assertThat(metar.conditions().visibility()).isEmpty();
            assertThat(metar.qnh()).isEmpty();
        }
    }
}
