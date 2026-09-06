package io.aerodyne.navdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiracCycleTest {

    @Test
    void parses_the_four_digit_identifier() {
        AiracCycle cycle = AiracCycle.parse("2413");

        assertThat(cycle.year()).isEqualTo(2024);
        assertThat(cycle.ordinal()).isEqualTo(13);
        assertThat(cycle.identifier()).isEqualTo("2413");
    }

    @ParameterizedTest
    @ValueSource(strings = {"241", "24133", "24AB", ""})
    void rejects_anything_that_is_not_four_digits(String bad) {
        assertThatThrownBy(() -> AiracCycle.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("four digits");
    }

    @Test
    void rejects_an_ordinal_no_year_can_have() {
        assertThatThrownBy(() -> new AiracCycle(2024, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordinal");
    }

    @Test
    void every_cycle_begins_on_a_thursday() {
        for (int ordinal = 1; ordinal <= 13; ordinal++) {
            assertThat(new AiracCycle(2024, ordinal).effectiveDate().getDayOfWeek())
                    .as("cycle 24%02d", ordinal)
                    .isEqualTo(DayOfWeek.THURSDAY);
        }
    }

    @Test
    void every_cycle_runs_for_twenty_eight_days() {
        AiracCycle cycle = new AiracCycle(2024, 5);

        assertThat(ChronoUnit.DAYS.between(cycle.effectiveDate(), cycle.expiryDate()))
                .isEqualTo(28);
    }

    @Test
    void a_date_resolves_to_the_cycle_that_covers_it() {
        AiracCycle cycle = new AiracCycle(2024, 7);

        assertThat(AiracCycle.effectiveOn(cycle.effectiveDate())).isEqualTo(cycle);
        assertThat(AiracCycle.effectiveOn(cycle.effectiveDate().plusDays(27))).isEqualTo(cycle);
        assertThat(AiracCycle.effectiveOn(cycle.expiryDate())).isEqualTo(cycle.next());
    }

    @Test
    void covers_is_inclusive_of_the_effective_date_and_exclusive_of_the_expiry() {
        AiracCycle cycle = new AiracCycle(2024, 3);

        assertThat(cycle.covers(cycle.effectiveDate())).isTrue();
        assertThat(cycle.covers(cycle.effectiveDate().minusDays(1))).isFalse();
        assertThat(cycle.covers(cycle.expiryDate().minusDays(1))).isTrue();
        assertThat(cycle.covers(cycle.expiryDate())).isFalse();
    }

    @Test
    void successive_cycles_are_contiguous_across_a_year_boundary() {
        AiracCycle last = AiracCycle.effectiveOn(LocalDate.of(2024, 12, 31));
        AiracCycle next = last.next();

        assertThat(next.effectiveDate()).isEqualTo(last.expiryDate());
        assertThat(next.previous()).isEqualTo(last);
    }

    @Test
    void the_first_cycle_of_a_year_is_numbered_one() {
        AiracCycle first = AiracCycle.effectiveOn(LocalDate.of(2024, 1, 1)).next();

        // 1 January can still fall inside the previous year's last cycle, so step forward once.
        assertThat(first.ordinal()).isEqualTo(1);
        assertThat(first.year()).isEqualTo(2024);
    }

    @Test
    void a_year_holds_thirteen_cycles_or_occasionally_fourteen() {
        int count = 0;
        AiracCycle cycle = AiracCycle.effectiveOn(LocalDate.of(2024, 1, 1)).next();
        while (cycle.year() == 2024) {
            count++;
            cycle = cycle.next();
        }

        assertThat(count).isBetween(13, 14);
    }

    @Test
    void reports_how_long_a_cycle_still_has_to_run() {
        AiracCycle cycle = new AiracCycle(2024, 9);

        assertThat(cycle.remainingAt(cycle.effectiveDate()).toDays()).isEqualTo(28);
        assertThat(cycle.remainingAt(cycle.expiryDate()).toDays()).isZero();
        assertThat(cycle.remainingAt(cycle.expiryDate().plusDays(3)).toDays()).isEqualTo(-3);
    }

    @Test
    void rejects_a_date_before_the_epoch_rather_than_extrapolating_backwards() {
        assertThatThrownBy(() -> AiracCycle.effectiveOn(LocalDate.of(1999, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
    }

    @Test
    void orders_by_effective_date() {
        assertThat(new AiracCycle(2024, 3)).isLessThan(new AiracCycle(2024, 4));
        assertThat(new AiracCycle(2025, 1)).isGreaterThan(new AiracCycle(2024, 13));
    }
}
