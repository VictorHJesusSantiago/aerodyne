package io.aerodyne.navdata.arinc;

import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.Navaid;
import io.aerodyne.navdata.NavaidType;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.WaypointUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Reads {@code /arinc/extract.arinc}, a small generated file that exercises every record type
 * the parser supports plus the malformed cases it has to survive. The generator that produced
 * it sits beside it as {@code generate_fixture.py}.
 */
class Arinc424ParserTest {

    private static Arinc424File file;

    @BeforeAll
    static void parseTheExtract() {
        try (InputStream stream = Arinc424ParserTest.class.getResourceAsStream("/arinc/extract.arinc")) {
            assertThat(stream).as("the test extract must be on the classpath").isNotNull();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.ISO_8859_1)) {
                file = new Arinc424Parser().parse(reader);
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the test extract", e);
        }
    }

    @Test
    void reads_the_AIRAC_cycle_the_records_were_issued_in() {
        assertThat(file.airacCycle()).contains(new AiracCycle(2024, 13));
    }

    @Nested
    @DisplayName("airports and runways")
    class Airports {

        @Test
        void reads_the_airport_primary_record() {
            Airport egll = airport("EGLL");

            assertThat(egll.iataCode()).isEqualTo("LHR");
            assertThat(egll.name()).isEqualTo("LONDON HEATHROW");
            assertThat(egll.elevationFt()).isEqualTo(83);
            assertThat(egll.transitionAltitudeFt()).isEqualTo(6000);
            assertThat(egll.publicMilitary()).isEqualTo("C");
            assertThat(egll.position().latitude()).isCloseTo(51.4797222, within(1e-6));
            assertThat(egll.position().longitude()).isCloseTo(-0.4644444, within(1e-6));
        }

        @Test
        void reads_the_longest_runway_field_as_hundreds_of_feet() {
            assertThat(airport("EGLL").longestRunwayFt()).isEqualTo(12_900);
            assertThat(airport("EGKK").longestRunwayFt()).isEqualTo(8_400);
        }

        @Test
        void attaches_runways_to_the_airport_they_name() {
            assertThat(airport("EGLL").runways())
                    .extracting(Runway::designator)
                    .containsExactlyInAnyOrder("RW27L", "RW09R");
            assertThat(airport("KJFK").runways()).hasSize(1);
            assertThat(airport("EGKK").runways()).isEmpty();
        }

        @Test
        void reads_the_runway_record() {
            Runway rw27l = airport("EGLL").runway("27L").orElseThrow();

            assertThat(rw27l.lengthFt()).isEqualTo(12_799);
            assertThat(rw27l.widthFt()).isEqualTo(164);
            assertThat(rw27l.thresholdElevationFt()).isEqualTo(79);
            assertThat(rw27l.ilsIdent()).isEqualTo("ILL");
            assertThat(rw27l.shortDesignator()).isEqualTo("27L");
        }

        @Test
        void reads_the_runway_bearing_as_tenths_of_a_degree() {
            assertThat(airport("EGLL").runway("27L").orElseThrow().magneticBearingDeg())
                    .isCloseTo(269.5, within(1e-9));
            assertThat(airport("EGLL").runway("09R").orElseThrow().magneticBearingDeg())
                    .isCloseTo(89.5, within(1e-9));
        }

        @Test
        void reads_the_signed_runway_gradient_as_thousandths_of_a_percent() {
            assertThat(airport("EGLL").runway("27L").orElseThrow().gradientPercent())
                    .isCloseTo(-0.001, within(1e-9));
            assertThat(airport("EGLL").runway("09R").orElseThrow().gradientPercent())
                    .isCloseTo(0.001, within(1e-9));
        }

        @Test
        void subtracts_a_displaced_threshold_from_the_landing_distance() {
            Runway rw09r = airport("EGLL").runway("09R").orElseThrow();

            assertThat(rw09r.displacedThresholdFt()).isEqualTo(306);
            assertThat(rw09r.landingDistanceAvailableFt()).isEqualTo(12_799 - 306);
        }

        @Test
        void keeps_an_airport_whose_elevation_is_missing_and_says_so() {
            assertThat(airport("EGKK").elevationFt()).isZero();
            assertThat(file.issues())
                    .anyMatch(i -> !i.isError() && i.message().contains("EGKK")
                            && i.message().contains("elevation"));
        }

        private Airport airport(String icao) {
            return file.airports().stream()
                    .filter(a -> a.icaoCode().equals(icao))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(icao + " was not parsed"));
        }
    }

    @Nested
    @DisplayName("navaids")
    class Navaids {

        @Test
        void reads_a_VHF_frequency_as_tens_of_kilohertz() {
            Navaid ock = navaid("OCK");

            assertThat(ock.frequencyKhz()).isEqualTo(115_300);
            assertThat(ock.frequencyMhz()).isCloseTo(115.3, within(1e-9));
        }

        @Test
        void reads_the_navaid_class_field_as_a_type() {
            assertThat(navaid("OCK").type()).isEqualTo(NavaidType.VOR_DME);
            assertThat(navaid("MID").type()).isEqualTo(NavaidType.VORTAC);
            assertThat(navaid("DME").type()).isEqualTo(NavaidType.DME);
            assertThat(navaid("LON").type()).isEqualTo(NavaidType.NDB);
        }

        @Test
        void reads_an_NDB_frequency_as_tenths_of_a_kilohertz() {
            assertThat(navaid("LON").frequencyKhz()).isEqualTo(355);
        }

        @Test
        void reads_station_declination_as_a_signed_tenth_of_a_degree() {
            assertThat(navaid("OCK").magneticVariationDeg()).isCloseTo(-0.3, within(1e-9));
            assertThat(navaid("DME").magneticVariationDeg()).isCloseTo(-2.1, within(1e-9));
        }

        @Test
        void turns_a_published_radial_into_a_true_course() {
            // Three tenths of a degree west of true, so a magnetic radial reads slightly higher.
            assertThat(navaid("OCK").radialToTrueCourse(90.0)).isCloseTo(89.7, within(1e-9));
        }

        @Test
        void reads_the_navaid_elevation_when_the_record_carries_one() {
            assertThat(navaid("OCK").elevationFeet()).hasValue(276.0);
            assertThat(navaid("LON").elevationFeet()).isEmpty();
        }

        @Test
        void classifies_an_NDB_as_a_different_kind_of_fix_from_a_VOR() {
            assertThat(navaid("OCK").kind()).isEqualTo(io.aerodyne.navdata.FixKind.VHF_NAVAID);
            assertThat(navaid("LON").kind()).isEqualTo(io.aerodyne.navdata.FixKind.NDB_NAVAID);
        }

        private Navaid navaid(String ident) {
            return file.navaids().stream()
                    .filter(n -> n.ident().equals(ident))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(ident + " was not parsed"));
        }
    }

    @Nested
    @DisplayName("waypoints")
    class Waypoints {

        @Test
        void reads_enroute_waypoints_with_their_region() {
            assertThat(file.waypoints())
                    .filteredOn(w -> w.ident().equals("KOLIN"))
                    .extracting(Waypoint::icaoRegion)
                    .containsExactlyInAnyOrder("EG", "SB");
        }

        @Test
        void reads_the_usage_field() {
            Waypoint kolin = waypoint("KOLIN", "EG");

            assertThat(kolin.usage()).isEqualTo(WaypointUsage.ENROUTE);
            assertThat(kolin.usage().allowsEnroute()).isTrue();
            assertThat(kolin.terminalAirport()).isNull();
        }

        @Test
        void marks_a_terminal_waypoint_with_the_airport_it_belongs_to() {
            Waypoint det26 = waypoint("DET26", "EG");

            assertThat(det26.usage()).isEqualTo(WaypointUsage.TERMINAL);
            assertThat(det26.usage().allowsEnroute()).isFalse();
            assertThat(det26.terminalAirport()).isEqualTo("EGLL");
        }

        private Waypoint waypoint(String ident, String region) {
            return file.waypoints().stream()
                    .filter(w -> w.ident().equals(ident) && w.icaoRegion().equals(region))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(ident + "/" + region + " was not parsed"));
        }
    }

    @Nested
    @DisplayName("airways")
    class Airways {

        @Test
        void assembles_an_airway_from_its_points_in_sequence_order() {
            Airway un862 = airway("UN862");

            assertThat(un862.points())
                    .extracting(io.aerodyne.navdata.AirwayPoint::fixIdent)
                    .containsExactly("KOLIN", "BEDEK", "SOVAT");
        }

        @Test
        void derives_legs_from_consecutive_points() {
            assertThat(airway("UN862").segments())
                    .extracting(Object::toString)
                    .containsExactly("UN862 KOLIN -> BEDEK", "UN862 BEDEK -> SOVAT");
        }

        @Test
        void reads_the_published_altitude_band() {
            var kolin = airway("UN862").pointNamed("KOLIN").orElseThrow();

            assertThat(kolin.minimumAltitudeFt()).isEqualTo(24_000);
            assertThat(kolin.maximumAltitudeFt()).isEqualTo(46_000);
            assertThat(kolin.permitsAltitude(35_000)).isTrue();
            assertThat(kolin.permitsAltitude(20_000)).isFalse();
        }

        @Test
        void reads_a_limit_written_as_a_flight_level() {
            assertThat(airway("L620").pointNamed("OCK").orElseThrow().maximumAltitudeFt())
                    .isEqualTo(19_500);
        }

        @Test
        void reads_the_level_band_and_the_direction_restriction() {
            var un862 = airway("UN862").pointNamed("KOLIN").orElseThrow();
            var l620 = airway("L620").pointNamed("OCK").orElseThrow();

            assertThat(un862.level()).isEqualTo(AirwayLevel.HIGH);
            assertThat(un862.direction()).isEqualTo(DirectionRestriction.BOTH);
            assertThat(l620.level()).isEqualTo(AirwayLevel.LOW);
            assertThat(l620.direction()).isEqualTo(DirectionRestriction.FORWARD);
        }

        @Test
        void a_one_way_airway_cannot_be_flown_backwards() {
            assertThat(airway("L620").between("OCK", "MID"))
                    .extracting(io.aerodyne.navdata.AirwayPoint::fixIdent)
                    .containsExactly("OCK", "MID");
            assertThat(airway("L620").between("MID", "OCK")).isEmpty();
        }

        @Test
        void a_two_way_airway_can_be_flown_either_way() {
            assertThat(airway("UN862").between("SOVAT", "KOLIN"))
                    .extracting(io.aerodyne.navdata.AirwayPoint::fixIdent)
                    .containsExactly("SOVAT", "BEDEK", "KOLIN");
        }

        @Test
        void a_fix_not_on_the_airway_yields_no_stretch() {
            assertThat(airway("UN862").between("KOLIN", "OCK")).isEmpty();
            assertThat(airway("UN862").connects("KOLIN", "OCK")).isFalse();
        }

        private Airway airway(String ident) {
            return file.airways().stream()
                    .filter(a -> a.ident().equals(ident))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(ident + " was not parsed"));
        }
    }

    @Nested
    @DisplayName("malformed and unsupported records")
    class Robustness {

        @Test
        void counts_the_records_it_does_not_read_rather_than_failing_on_them() {
            assertThat(file.recordCount(ArincRecordType.UNSUPPORTED)).isPositive();
        }

        @Test
        void reports_a_truncated_line_as_an_error_and_carries_on() {
            assertThat(file.errors())
                    .anyMatch(i -> i.message().contains("too short to read"));
        }

        @Test
        void drops_a_fix_whose_position_will_not_parse_rather_than_placing_it_at_the_equator() {
            assertThat(file.waypoints()).noneMatch(w -> w.ident().equals("BADFX"));
            assertThat(file.errors())
                    .anyMatch(i -> i.message().contains("BADFX") && i.message().contains("position"));
        }

        @Test
        void drops_an_airway_point_with_no_sequence_number() {
            assertThat(file.airways()).noneMatch(a -> a.ident().equals("UQ99"));
            assertThat(file.errors())
                    .anyMatch(i -> i.message().contains("UQ99") && i.message().contains("sequence"));
        }

        @Test
        void skips_continuation_records_without_duplicating_the_navaid() {
            assertThat(file.navaids()).filteredOn(n -> n.ident().equals("OCK")).hasSize(1);
        }

        @Test
        void reports_what_the_file_held() {
            assertThat(file.objectCount()).isEqualTo(
                    file.airports().size() + file.runways().size() + file.navaids().size()
                            + file.waypoints().size() + file.airways().size()
                            + file.procedures().size() + file.gridMora().size());
            assertThat(file.toString()).contains("cycle=2413").contains("airports=3");
        }
    }
}
