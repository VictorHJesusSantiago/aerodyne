package io.aerodyne.navdata.procedure;

import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.arinc.Arinc424File;
import io.aerodyne.navdata.arinc.Arinc424Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Terminal procedures, read from the generated extract.
 *
 * <p>The fixture holds one departure with a runway transition, a common route and two enroute
 * transitions; one arrival, whose route types mean the opposite of the departure's; and one ILS
 * approach with a transition and a final. Between them they exercise every part of the
 * assembly.
 */
class ProcedureParsingTest {

    private static Arinc424File file;
    private static NavigationDatabase database;

    @BeforeAll
    static void parseTheExtract() {
        try (InputStream stream =
                     ProcedureParsingTest.class.getResourceAsStream("/arinc/extract.arinc");
             Reader reader = new InputStreamReader(stream, StandardCharsets.ISO_8859_1)) {
            file = new Arinc424Parser().parse(reader);
            database = NavigationDatabase.from(file);
        } catch (Exception e) {
            throw new AssertionError("could not read the test extract", e);
        }
    }

    private static Procedure procedure(String ident) {
        return database.procedure("EGLL", ident)
                .orElseThrow(() -> new AssertionError(ident + " was not parsed"));
    }

    @Nested
    @DisplayName("departures")
    class Departures {

        @Test
        void reads_a_departure_and_its_segments() {
            Procedure sid = procedure("KOLI1J");

            assertThat(sid.type()).isEqualTo(ProcedureType.SID);
            assertThat(sid.airportIcao()).isEqualTo("EGLL");
            assertThat(sid.runwayTransitions()).hasSize(1);
            assertThat(sid.commonRoute()).isPresent();
            assertThat(sid.enrouteTransitions()).hasSize(2);
        }

        @Test
        void the_route_type_decides_which_segment_a_record_belongs_to() {
            Procedure sid = procedure("KOLI1J");

            assertThat(sid.runwayTransitions().get(0).identifier()).isEqualTo("RW27L");
            assertThat(sid.enrouteTransitions())
                    .extracting(ProcedureTransition::identifier)
                    .containsExactly("BEDEK", "SOVAT");
        }

        @Test
        void assembles_runway_then_common_then_enroute() {
            List<ProcedureLeg> legs = procedure("KOLI1J").assemble("27L", "BEDEK");

            assertThat(legs).extracting(ProcedureLeg::legType)
                    .containsExactly(LegType.VA, LegType.DF, LegType.TF, LegType.TF, LegType.TF);
            assertThat(procedure("KOLI1J").fixSequence("27L", "BEDEK"))
                    .containsExactly("DET26", "OCK", "KOLIN", "BEDEK");
        }

        @Test
        void a_different_enroute_transition_gives_a_different_ending() {
            assertThat(procedure("KOLI1J").fixSequence("27L", "SOVAT"))
                    .containsExactly("DET26", "OCK", "KOLIN", "SOVAT");
        }

        @Test
        void a_transition_that_is_not_published_leaves_that_segment_out() {
            // No transition named ZZZZZ exists, so only the runway and common segments assemble.
            assertThat(procedure("KOLI1J").fixSequence("27L", "ZZZZZ"))
                    .containsExactly("DET26", "OCK", "KOLIN");
        }

        @Test
        void reports_the_runways_it_serves() {
            assertThat(procedure("KOLI1J").runways()).containsExactly("RW27L");
            assertThat(procedure("KOLI1J").servesRunway("27L")).isTrue();
            assertThat(procedure("KOLI1J").servesRunway("09R")).isFalse();
        }
    }

    @Nested
    @DisplayName("arrivals")
    class Arrivals {

        @Test
        void an_arrivals_route_types_mean_the_opposite_of_a_departures() {
            Procedure star = procedure("BEDE1A");

            assertThat(star.type()).isEqualTo(ProcedureType.STAR);
            // Route type 1 is an enroute transition on an arrival and a runway one on a departure.
            assertThat(star.enrouteTransitions())
                    .extracting(ProcedureTransition::identifier).containsExactly("BEDEK");
            assertThat(star.runwayTransitions())
                    .extracting(ProcedureTransition::identifier).containsExactly("RW27L");
        }

        @Test
        void assembles_enroute_then_common_then_runway() {
            assertThat(procedure("BEDE1A").fixSequence("27L", "BEDEK"))
                    .containsExactly("BEDEK", "KOLIN", "OCK");
        }
    }

    @Nested
    @DisplayName("approaches")
    class Approaches {

        @Test
        void reads_an_approach_and_tells_its_transition_from_its_final() {
            Procedure approach = procedure("I27L");

            assertThat(approach.type()).isEqualTo(ProcedureType.APPROACH);
            assertThat(approach.approachTransitions())
                    .extracting(ProcedureTransition::identifier).containsExactly("OCK");
            assertThat(approach.commonRoute())
                    .get().extracting(ProcedureTransition::kind).isEqualTo(TransitionKind.FINAL);
        }

        @Test
        void the_route_type_says_what_kind_of_approach_it_is() {
            Procedure approach = procedure("I27L");

            assertThat(approach.routeType()).isEqualTo('I');
            assertThat(approach.approachKind()).isEqualTo("ILS");
            assertThat(approach.isPrecisionApproach()).isTrue();
        }

        @Test
        void assembles_the_transition_then_the_final() {
            assertThat(procedure("I27L").fixSequence("27L", "OCK"))
                    .containsExactly("OCK", "DET26", "DET26", "RW27L");
        }

        @Test
        void reads_the_published_descent_angle() {
            List<ProcedureLeg> legs = procedure("I27L").assemble(null, "OCK");

            assertThat(legs.get(legs.size() - 1).verticalAngleDeg()).isCloseTo(-3.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("legs")
    class Legs {

        private ProcedureLeg leg(String procedure, String transition, int index) {
            return procedure(procedure).assemble("27L", transition).get(index);
        }

        @Test
        void a_heading_to_an_altitude_leg_has_no_fix_to_end_at() {
            ProcedureLeg va = leg("KOLI1J", "BEDEK", 0);

            assertThat(va.legType()).isEqualTo(LegType.VA);
            assertThat(va.legType().isHeadingLeg()).isTrue();
            assertThat(va.legType().terminator()).isEqualTo(LegType.Terminator.ALTITUDE);
            assertThat(va.endsAtFix()).isFalse();
            assertThat(va.isUnresolvable()).isTrue();
            assertThat(va.altitude().kind()).isEqualTo(AltitudeConstraint.Kind.AT);
            assertThat(va.altitude().permits(800)).isTrue();
            assertThat(va.altitude().permits(1200)).isFalse();
        }

        @Test
        void a_departure_with_a_heading_leg_is_not_fully_resolvable() {
            assertThat(procedure("KOLI1J").isFullyResolvable("27L", "BEDEK")).isFalse();
            assertThat(procedure("BEDE1A").isFullyResolvable("27L", "BEDEK")).isTrue();
        }

        @Test
        void reads_an_at_or_above_constraint_and_a_speed_limit() {
            ProcedureLeg df = leg("KOLI1J", "BEDEK", 1);

            assertThat(df.legType()).isEqualTo(LegType.DF);
            assertThat(df.altitude().kind()).isEqualTo(AltitudeConstraint.Kind.AT_OR_ABOVE);
            assertThat(df.altitude().lowerLimitFt()).hasValue(3000);
            assertThat(df.altitude().upperLimitFt()).isEmpty();
            assertThat(df.altitude().permits(5000)).isTrue();
            assertThat(df.altitude().permits(2000)).isFalse();
            assertThat(df.speed().kind()).isEqualTo(SpeedConstraint.Kind.MAXIMUM);
            assertThat(df.speed().speedKt()).isEqualTo(220);
            assertThat(df.speed().permits(200)).isTrue();
            assertThat(df.speed().permits(250)).isFalse();
        }

        @Test
        void reads_an_altitude_window_the_right_way_round() {
            ProcedureLeg between = leg("KOLI1J", "BEDEK", 3);

            assertThat(between.altitude().kind()).isEqualTo(AltitudeConstraint.Kind.BETWEEN);
            assertThat(between.altitude().upperLimitFt()).hasValue(24_000);
            assertThat(between.altitude().lowerLimitFt()).hasValue(10_000);
            assertThat(between.altitude().permits(15_000)).isTrue();
            assertThat(between.altitude().permits(30_000)).isFalse();
            assertThat(between.altitude().permits(5_000)).isFalse();
            assertThat(between.altitude().toChartString()).isEqualTo("10000A24000B");
        }

        @Test
        void reads_an_at_or_below_constraint() {
            ProcedureLeg leg = procedure("BEDE1A").assemble("27L", "BEDEK").get(1);

            assertThat(leg.altitude().kind()).isEqualTo(AltitudeConstraint.Kind.AT_OR_BELOW);
            assertThat(leg.altitude().upperLimitFt()).hasValue(20_000);
            assertThat(leg.altitude().lowerLimitFt()).isEmpty();
        }

        @Test
        void reads_the_course_and_distance_as_tenths() {
            ProcedureLeg tf = leg("KOLI1J", "BEDEK", 2);

            assertThat(tf.course()).hasValue(210.0);
            assertThat(tf.distanceNm()).hasValue(12.0);
        }

        @Test
        void reads_the_turn_direction() {
            assertThat(leg("KOLI1J", "BEDEK", 2).turnDirection()).isEqualTo(TurnDirection.LEFT);
            assertThat(leg("KOLI1J", "BEDEK", 2).turnDirection().isMandatory()).isTrue();
            assertThat(leg("KOLI1J", "BEDEK", 3).turnDirection())
                    .isEqualTo(TurnDirection.UNSPECIFIED);
        }

        @Test
        void reads_a_fly_over_waypoint_from_the_description_code() {
            assertThat(leg("KOLI1J", "BEDEK", 1).isFlyOver()).isTrue();
            assertThat(leg("KOLI1J", "BEDEK", 2).isFlyOver()).isFalse();
        }

        @Test
        void marks_the_leg_that_ends_a_transition() {
            List<ProcedureLeg> legs = procedure("KOLI1J").assemble("27L", "BEDEK");

            assertThat(legs.get(legs.size() - 1).endsTransition()).isTrue();
        }

        @Test
        void renders_a_leg_the_way_a_chart_writes_it() {
            assertThat(leg("KOLI1J", "BEDEK", 1).toChartString())
                    .isEqualTo("DF DET26 3000A 220K (flyover)");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        void keeps_a_leg_whose_path_and_terminator_it_does_not_know_and_warns() {
            Procedure odd = procedure("ODD1X");

            assertThat(odd.assemble()).singleElement()
                    .extracting(ProcedureLeg::legType).isEqualTo(LegType.UNKNOWN);
            assertThat(file.issues())
                    .anyMatch(issue -> !issue.isError()
                            && issue.message().contains("unrecognised path and terminator"));
        }

        @Test
        void a_procedure_of_a_single_leg_is_kept_unlike_a_one_point_airway() {
            assertThat(database.procedure("EGLL", "ODD1X")).isPresent();
        }

        @Test
        void counts_the_procedure_records_it_read() {
            assertThat(file.recordCount(io.aerodyne.navdata.arinc.ArincRecordType.SID))
                    .isEqualTo(7);
            assertThat(file.recordCount(io.aerodyne.navdata.arinc.ArincRecordType.STAR))
                    .isEqualTo(3);
            assertThat(file.recordCount(io.aerodyne.navdata.arinc.ArincRecordType.APPROACH))
                    .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("the navigation database")
    class Indexing {

        @Test
        void indexes_procedures_by_aerodrome() {
            assertThat(database.procedures("EGLL"))
                    .extracting(Procedure::ident)
                    .containsExactlyInAnyOrder("KOLI1J", "BEDE1A", "I27L", "ODD1X");
            assertThat(database.procedures("KJFK")).isEmpty();
            assertThat(database.procedureCount()).isEqualTo(4);
        }

        @Test
        void filters_by_kind() {
            assertThat(database.procedures("EGLL", ProcedureType.STAR))
                    .extracting(Procedure::ident).containsExactly("BEDE1A");
            assertThat(database.procedures("EGLL", ProcedureType.APPROACH))
                    .extracting(Procedure::ident).containsExactly("I27L");
        }

        @Test
        void filters_by_runway_and_keeps_the_ones_that_serve_every_runway() {
            assertThat(database.proceduresForRunway("EGLL", ProcedureType.SID, "27L"))
                    .extracting(Procedure::ident)
                    // ODD1X has no runway segment, so it serves every runway.
                    .containsExactlyInAnyOrder("KOLI1J", "ODD1X");
            assertThat(database.proceduresForRunway("EGLL", ProcedureType.SID, "09R"))
                    .extracting(Procedure::ident).containsExactly("ODD1X");
        }

        @Test
        void looks_a_procedure_up_by_name_case_insensitively() {
            assertThat(database.procedure("egll", "koli1j")).isPresent();
            assertThat(database.procedure("EGLL", "NOSUCH")).isEmpty();
        }
    }
}
