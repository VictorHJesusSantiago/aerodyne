package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.arinc.Arinc424File;
import io.aerodyne.navdata.arinc.Arinc424Parser;
import io.aerodyne.navdata.arinc.ArincParseIssue;
import io.aerodyne.navdata.arinc.ArincRecordType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grid minimum off-route altitudes, from the ARINC record through to the lookup.
 *
 * <p>The distinction this whole feature turns on is between a square that was surveyed and found
 * to be flat, and a square nobody surveyed. Most of what follows is about keeping those two
 * apart, because a plan that confuses them descends into terrain.
 */
class GridMoraTest {

    private static Arinc424File parseFixture() {
        return new Arinc424Parser().parse(Path.of("src/test/resources/arinc/extract.arinc"));
    }

    @Nested
    @DisplayName("the record itself")
    class Record {

        private final GridMora band = new GridMora(51, -5,
                new int[] {2300, 1800, 1200, GridMora.UNKNOWN, 700});

        @Test
        void a_position_resolves_to_the_square_it_falls_in() {
            assertThat(band.altitudeAt(GeoPoint.of(51.5, -4.5))).hasValue(2300);
            assertThat(band.altitudeAt(GeoPoint.of(51.9, -3.1))).hasValue(1800);
            assertThat(band.altitudeAt(GeoPoint.of(51.0, -1.0))).hasValue(700);
        }

        @Test
        void an_unsurveyed_square_answers_nothing_rather_than_zero() {
            assertThat(band.altitudeAt(GeoPoint.of(51.5, -1.5))).isEmpty();
            assertThat(band.covers(GeoPoint.of(51.5, -1.5))).isTrue();
        }

        @Test
        void a_position_outside_the_band_is_not_covered() {
            assertThat(band.covers(GeoPoint.of(52.5, -4.5))).isFalse();
            assertThat(band.covers(GeoPoint.of(51.5, 4.5))).isFalse();
            assertThat(band.altitudeAt(GeoPoint.of(52.5, -4.5))).isEmpty();
        }

        @Test
        void the_edges_are_reported_the_way_the_file_publishes_them() {
            assertThat(band.southLatitude()).isEqualTo(51);
            assertThat(band.westLongitude()).isEqualTo(-5);
            assertThat(band.eastLongitude()).isEqualTo(0);
            assertThat(band.surveyedCount()).isEqualTo(4);
        }

        @Test
        void it_is_a_value_type_so_two_identical_bands_are_equal() {
            GridMora same = new GridMora(51, -5,
                    new int[] {2300, 1800, 1200, GridMora.UNKNOWN, 700});

            assertThat(band).isEqualTo(same).hasSameHashCodeAs(same);
        }

        @Test
        void the_altitudes_cannot_be_changed_through_the_accessor() {
            int[] taken = band.altitudesFt();
            taken[0] = 99_999;

            assertThat(band.altitudeAt(GeoPoint.of(51.5, -4.5))).hasValue(2300);
        }

        @Test
        void a_band_outside_the_world_is_refused() {
            assertThatThrownBy(() -> new GridMora(91, 0, new int[] {100}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("latitude");
            assertThatThrownBy(() -> new GridMora(0, 181, new int[] {100}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("longitude");
        }
    }

    @Nested
    @DisplayName("reading it out of an ARINC file")
    class Parsing {

        private final Arinc424File file = parseFixture();

        @Test
        void the_bands_are_read_and_counted() {
            assertThat(file.gridMora()).hasSize(3);
            assertThat(file.recordCounts().get(ArincRecordType.GRID_MORA)).isEqualTo(4);
        }

        @Test
        void the_values_are_hundreds_of_feet() {
            GridMora band = file.gridMora().get(0);

            assertThat(band.southLatitude()).isEqualTo(51);
            assertThat(band.westLongitude()).isEqualTo(-5);
            assertThat(band.altitudeAt(GeoPoint.of(51.5, -4.5))).hasValue(2300);
        }

        @Test
        void a_southern_or_western_band_keeps_its_sign() {
            GridMora southern = file.gridMora().get(1);

            assertThat(southern.southLatitude()).isEqualTo(-23);
            assertThat(southern.westLongitude()).isEqualTo(-47);
            assertThat(southern.altitudeAt(GeoPoint.of(-22.5, -46.5))).hasValue(8500);
        }

        @Test
        void a_UNK_square_is_read_as_unsurveyed() {
            assertThat(file.gridMora().get(0).altitudeAt(GeoPoint.of(51.5, -1.5))).isEmpty();
        }

        /**
         * The squares are fixed-width and positional, so a record short of its thirty values
         * must leave the rest unknown rather than sliding the ones it has along.
         */
        @Test
        void a_short_record_leaves_the_squares_it_does_not_carry_unknown() {
            GridMora band = file.gridMora().get(0);

            assertThat(band.altitudesFt()).hasSize(GridMora.SQUARES_PER_RECORD);
            assertThat(band.surveyedCount()).isEqualTo(4);
            assertThat(band.altitudeAt(GeoPoint.of(51.5, 10.5))).isEmpty();
        }

        @Test
        void a_square_that_is_not_a_number_is_left_unsurveyed_with_a_warning() {
            GridMora band = file.gridMora().get(2);

            assertThat(band.altitudeAt(GeoPoint.of(40.5, 8.5))).hasValue(1200);
            assertThat(band.altitudeAt(GeoPoint.of(40.5, 9.5))).isEmpty();
            assertThat(band.altitudeAt(GeoPoint.of(40.5, 10.5))).hasValue(2000);
            assertThat(file.issues())
                    .filteredOn(issue -> issue.recordType() == ArincRecordType.GRID_MORA)
                    .anySatisfy(issue -> assertThat(issue.message())
                            .contains("not numbers"));
        }

        @Test
        void a_record_with_no_readable_starting_square_is_dropped_with_an_error() {
            assertThat(file.errors())
                    .filteredOn(issue -> issue.recordType() == ArincRecordType.GRID_MORA)
                    .singleElement()
                    .extracting(ArincParseIssue::message)
                    .asString()
                    .contains("starting square");
        }
    }

    @Nested
    @DisplayName("looking it up along a route")
    class Along {

        private final MinimumAltitudeGrid grid = new MinimumAltitudeGrid(List.of(
                new GridMora(51, -30, filled(2300, 1800, 1200, GridMora.UNKNOWN, 700)),
                new GridMora(50, -30, filled(900, 4100, 600))));

        private static int[] filled(int... values) {
            int[] squares = new int[GridMora.SQUARES_PER_RECORD];
            java.util.Arrays.fill(squares, GridMora.UNKNOWN);
            System.arraycopy(values, 0, squares, 0, values.length);
            return squares;
        }

        @Test
        void a_position_resolves_through_the_index() {
            assertThat(grid.altitudeAt(GeoPoint.of(51.5, -29.5))).hasValue(2300);
            assertThat(grid.altitudeAt(GeoPoint.of(50.5, -28.5))).hasValue(4100);
            assertThat(grid.altitudeAt(GeoPoint.of(49.5, -29.5))).isEmpty();
        }

        @Test
        void a_walk_finds_the_highest_square_the_route_crosses() {
            MinimumAltitudeGrid.Along along = grid.along(
                    List.of(GeoPoint.of(50.5, -29.5), GeoPoint.of(50.5, -27.5)), 10.0);

            assertThat(along.highest()).hasValue(4100);
            assertThat(along.highestAt()).isNotNull();
            assertThat(along.isComplete()).isTrue();
        }

        @Test
        void squares_nobody_surveyed_are_reported_rather_than_counted_as_zero() {
            MinimumAltitudeGrid.Along along = grid.along(
                    List.of(GeoPoint.of(51.5, -29.5), GeoPoint.of(51.5, -25.5)), 10.0);

            assertThat(along.hasGaps()).isTrue();
            assertThat(along.isComplete()).isFalse();
            assertThat(along.unsurveyed()).isPositive();
            assertThat(along.highest()).hasValue(2300);
        }

        @Test
        void a_route_entirely_off_the_grid_reports_nothing_known() {
            MinimumAltitudeGrid.Along along = grid.along(
                    List.of(GeoPoint.of(10.0, 10.0), GeoPoint.of(11.0, 11.0)), 30.0);

            assertThat(along.highest()).isEmpty();
            assertThat(along.hasGaps()).isTrue();
            assertThat(along.clearedBy(1_000)).isTrue();
        }

        @Test
        void a_level_is_cleared_only_when_it_is_at_or_above_the_highest_square() {
            MinimumAltitudeGrid.Along along = grid.along(
                    List.of(GeoPoint.of(50.5, -29.5), GeoPoint.of(50.5, -27.5)), 10.0);

            assertThat(along.clearedBy(4_100)).isTrue();
            assertThat(along.clearedBy(35_000)).isTrue();
            assertThat(along.clearedBy(4_000)).isFalse();
        }

        @Test
        void an_empty_grid_answers_unknown_for_everything() {
            assertThat(MinimumAltitudeGrid.EMPTY.isEmpty()).isTrue();
            assertThat(MinimumAltitudeGrid.EMPTY.altitudeAt(GeoPoint.of(51.5, -4.5))).isEmpty();
            assertThat(MinimumAltitudeGrid.EMPTY
                    .along(List.of(GeoPoint.of(51.0, -5.0), GeoPoint.of(52.0, -4.0)), 30.0)
                    .highest()).isEqualTo(OptionalInt.empty());
        }
    }

    @Nested
    @DisplayName("through the navigation database")
    class Database {

        @Test
        void a_parsed_cycle_carries_its_grid() {
            NavigationDatabase database = NavigationDatabase.from(parseFixture());

            assertThat(database.minimumAltitudes().isEmpty()).isFalse();
            assertThat(database.minimumAltitudes().size()).isEqualTo(3);
            assertThat(database.minimumAltitudes().altitudeAt(GeoPoint.of(51.5, -4.5)))
                    .hasValue(2300);
        }

        @Test
        void a_cycle_built_without_one_answers_unknown_rather_than_failing() {
            NavigationDatabase database = NavigationDatabase.builder()
                    .cycle(new AiracCycle(2024, 13))
                    .build();

            assertThat(database.minimumAltitudes().isEmpty()).isTrue();
            assertThat(database.minimumAltitudes().altitudeAt(GeoPoint.of(51.5, -4.5))).isEmpty();
        }
    }
}
