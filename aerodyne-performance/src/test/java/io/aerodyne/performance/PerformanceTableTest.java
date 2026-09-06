package io.aerodyne.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PerformanceTableTest {

    private final PerformanceTable table = new PerformanceTable("test",
            new double[]{50_000, 70_000},
            new double[]{30_000, 40_000},
            new double[][]{
                    {100, 200},
                    {300, 400},
            });

    @Test
    void a_query_on_a_tabulated_point_returns_that_value() {
        assertThat(table.valueAt(50_000, 30_000)).isEqualTo(100.0);
        assertThat(table.valueAt(70_000, 40_000)).isEqualTo(400.0);
    }

    @Test
    void interpolates_along_the_altitude_axis() {
        assertThat(table.valueAt(50_000, 35_000)).isCloseTo(150.0, within(1e-9));
    }

    @Test
    void interpolates_along_the_weight_axis() {
        assertThat(table.valueAt(60_000, 30_000)).isCloseTo(200.0, within(1e-9));
    }

    @Test
    void interpolates_bilinearly_across_both() {
        assertThat(table.valueAt(60_000, 35_000)).isCloseTo(250.0, within(1e-9));
    }

    @Test
    void clamps_rather_than_extrapolating_past_the_edge() {
        assertThat(table.valueAt(20_000, 30_000)).isEqualTo(100.0);
        assertThat(table.valueAt(90_000, 40_000)).isEqualTo(400.0);
        assertThat(table.valueAt(50_000, 60_000)).isEqualTo(200.0);
        assertThat(table.valueAt(50_000, 0)).isEqualTo(100.0);
    }

    @Test
    void says_whether_a_query_was_inside_the_tabulated_range() {
        assertThat(table.covers(60_000, 35_000)).isTrue();
        assertThat(table.covers(90_000, 35_000)).isFalse();
        assertThat(table.covers(60_000, 45_000)).isFalse();
    }

    @Test
    void reports_the_extent_of_its_axes() {
        assertThat(table.minimumWeightKg()).isEqualTo(50_000.0);
        assertThat(table.maximumWeightKg()).isEqualTo(70_000.0);
        assertThat(table.minimumAltitudeFt()).isEqualTo(30_000.0);
        assertThat(table.maximumAltitudeFt()).isEqualTo(40_000.0);
    }

    @Test
    void a_single_column_table_still_answers_for_every_altitude() {
        PerformanceTable oneColumn = new PerformanceTable("optimum altitude",
                new double[]{50_000, 70_000}, new double[]{0},
                new double[][]{{39_000}, {35_000}});

        assertThat(oneColumn.valueAt(60_000, 0)).isCloseTo(37_000.0, within(1e-9));
        assertThat(oneColumn.valueAt(60_000, 12_345)).isCloseTo(37_000.0, within(1e-9));
    }

    @Test
    void finds_where_a_value_falls_below_a_threshold() {
        PerformanceTable rateOfClimb = new PerformanceTable("rate of climb",
                new double[]{50_000, 70_000},
                new double[]{20_000, 30_000, 40_000},
                new double[][]{
                        {2000, 1000, 200},
                        {1500, 700, 100},
                });

        // At 50 t the rate passes 300 ft/min between 30 000 and 40 000 ft.
        assertThat(rateOfClimb.altitudeWhereValueFallsBelow(50_000, 300.0))
                .isCloseTo(38_750.0, within(50.0));
    }

    @Test
    void reports_the_top_of_the_table_when_the_threshold_is_never_crossed() {
        assertThat(table.altitudeWhereValueFallsBelow(50_000, 0.0)).isEqualTo(40_000.0);
    }

    @Test
    void refuses_axes_that_do_not_ascend() {
        assertThatThrownBy(() -> new PerformanceTable("bad",
                new double[]{70_000, 50_000}, new double[]{30_000},
                new double[][]{{1}, {2}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must ascend");
    }

    @Test
    void refuses_a_value_grid_that_does_not_match_its_axes() {
        assertThatThrownBy(() -> new PerformanceTable("bad",
                new double[]{50_000, 70_000}, new double[]{30_000, 40_000},
                new double[][]{{1, 2}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 weight rows");
    }
}
