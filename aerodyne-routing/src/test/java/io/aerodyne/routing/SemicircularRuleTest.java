package io.aerodyne.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SemicircularRuleTest {

    private static final double EASTBOUND = 90.0;
    private static final double WESTBOUND = 270.0;

    @ParameterizedTest
    @CsvSource({"0, true", "90, true", "179, true", "180, false", "270, false", "359, false"})
    void the_eastbound_half_runs_from_000_up_to_but_not_including_180(double track, boolean eastbound) {
        assertThat(SemicircularRule.isEastbound(track)).isEqualTo(eastbound);
    }

    @Test
    void a_track_beyond_the_compass_is_normalised_first() {
        assertThat(SemicircularRule.isEastbound(450.0)).isTrue();
        assertThat(SemicircularRule.isEastbound(-90.0)).isFalse();
    }

    @Test
    void eastbound_tracks_fly_odd_thousands() {
        assertThat(SemicircularRule.isAvailable(EASTBOUND, 350)).isTrue();
        assertThat(SemicircularRule.isAvailable(EASTBOUND, 340)).isFalse();
        assertThat(SemicircularRule.isAvailable(EASTBOUND, 290)).isTrue();
    }

    @Test
    void westbound_tracks_fly_even_thousands() {
        assertThat(SemicircularRule.isAvailable(WESTBOUND, 340)).isTrue();
        assertThat(SemicircularRule.isAvailable(WESTBOUND, 350)).isFalse();
        assertThat(SemicircularRule.isAvailable(WESTBOUND, 400)).isTrue();
    }

    @Test
    void the_levels_step_by_four_thousand_feet_above_FL410() {
        assertThat(SemicircularRule.availableLevels(EASTBOUND, 420, 540))
                .containsExactly(450, 490, 530);
        assertThat(SemicircularRule.availableLevels(WESTBOUND, 420, 540))
                .containsExactly(430, 470, 510);
    }

    @Test
    void FL410_is_the_top_of_the_thousand_foot_bands() {
        assertThat(SemicircularRule.isAvailable(EASTBOUND, 410)).isTrue();
        assertThat(SemicircularRule.isAvailable(EASTBOUND, 430)).isFalse();
        assertThat(SemicircularRule.isAvailable(WESTBOUND, 420)).isFalse();
    }

    @Test
    void lists_the_levels_available_across_a_band() {
        assertThat(SemicircularRule.availableLevels(EASTBOUND, 290, 410))
                .containsExactly(290, 310, 330, 350, 370, 390, 410);
    }

    @Test
    void opposite_directions_never_share_a_level() {
        for (int level = 10; level <= 540; level += 10) {
            assertThat(SemicircularRule.isAvailable(EASTBOUND, level)
                    && SemicircularRule.isAvailable(WESTBOUND, level))
                    .as("FL%d", level)
                    .isFalse();
        }
    }

    @Test
    void picks_the_available_level_nearest_the_one_wanted() {
        assertThat(SemicircularRule.nearestAvailableLevel(EASTBOUND, 340, 290, 410)).hasValue(350);
        assertThat(SemicircularRule.nearestAvailableLevel(WESTBOUND, 350, 290, 410)).hasValue(360);
        assertThat(SemicircularRule.nearestAvailableLevel(EASTBOUND, 350, 290, 410)).hasValue(350);
    }

    @Test
    void a_tie_goes_to_the_higher_level_where_the_range_is_better() {
        // FL340 is equidistant from FL330 and FL350; the eastbound answer is the upper one.
        assertThat(SemicircularRule.nearestAvailableLevel(EASTBOUND, 340, 290, 410)).hasValue(350);
    }

    @Test
    void reports_nothing_when_the_band_holds_no_available_level() {
        assertThat(SemicircularRule.nearestAvailableLevel(EASTBOUND, 340, 340, 340)).isEmpty();
    }

    @Test
    void the_step_climb_is_two_thousand_feet_in_the_ordinary_bands() {
        assertThat(SemicircularRule.nextLevelUp(EASTBOUND, 350, 410)).hasValue(370);
        assertThat(SemicircularRule.nextLevelUp(WESTBOUND, 340, 410)).hasValue(360);
    }

    @Test
    void the_step_climb_is_four_thousand_feet_above_FL410() {
        assertThat(SemicircularRule.nextLevelUp(EASTBOUND, 450, 540)).hasValue(490);
    }

    @Test
    void there_is_no_step_above_the_ceiling_given() {
        assertThat(SemicircularRule.nextLevelUp(EASTBOUND, 410, 410)).isEmpty();
    }

    @Test
    void the_RVSM_band_is_the_one_that_needs_approval() {
        assertThat(SemicircularRule.requiresRvsmApproval(350)).isTrue();
        assertThat(SemicircularRule.requiresRvsmApproval(290)).isTrue();
        assertThat(SemicircularRule.requiresRvsmApproval(410)).isTrue();
        assertThat(SemicircularRule.requiresRvsmApproval(280)).isFalse();
        assertThat(SemicircularRule.requiresRvsmApproval(430)).isFalse();
    }
}
