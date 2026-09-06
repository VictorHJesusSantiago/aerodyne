package io.aerodyne.performance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FuelPolicyTest {

    /** Two and a half tonnes an hour holding, and 200 kg of taxi fuel. */
    private final FuelPolicy policy = FuelPolicy.standard(2_400.0, 200.0);

    @Test
    void contingency_is_five_percent_of_the_trip_fuel() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 0);

        assertThat(fuel.contingencyKg()).isCloseTo(500.0, within(1e-6));
    }

    @Test
    void a_short_sector_gets_the_five_minute_floor_instead() {
        FuelBreakdown fuel = policy.apply(1_000, 800, 0, 0);

        // Five percent of a tonne is 50 kg; five minutes of holding is 200 kg.
        assertThat(fuel.contingencyKg()).isCloseTo(200.0, within(1e-6));
    }

    @Test
    void the_final_reserve_is_thirty_minutes_of_holding() {
        assertThat(policy.apply(10_000, 1_800, 0, 0).finalReserveKg())
                .isCloseTo(1_200.0, within(1e-6));
    }

    @Test
    void takeoff_fuel_is_everything_but_the_taxi() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 0);

        assertThat(fuel.takeoffFuelKg()).isCloseTo(10_000 + 500 + 1_800 + 1_200, within(1e-6));
    }

    @Test
    void ramp_fuel_is_the_takeoff_fuel_plus_the_taxi() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 0);

        assertThat(fuel.rampFuelKg()).isCloseTo(fuel.takeoffFuelKg() + 200.0, within(1e-6));
    }

    @Test
    void the_commanders_extra_counts_towards_the_load_but_not_the_minimum() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 1_000);

        assertThat(fuel.minimumTakeoffFuelKg())
                .isCloseTo(fuel.takeoffFuelKg() - 1_000.0, within(1e-6));
    }

    @Test
    void additional_fuel_does_count_towards_the_minimum() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 900, 0);

        assertThat(fuel.minimumTakeoffFuelKg()).isCloseTo(fuel.takeoffFuelKg(), within(1e-6));
        assertThat(fuel.additionalKg()).isEqualTo(900.0);
    }

    @Test
    void the_plan_leaves_the_final_reserve_intact_after_a_diversion() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 0);

        assertThat(fuel.fuelOnLandingKg()).isCloseTo(500 + 1_800 + 1_200, within(1e-6));
        assertThat(fuel.fuelAtAlternateKg()).isCloseTo(500 + 1_200, within(1e-6));
        assertThat(fuel.protectsFinalReserve()).isTrue();
    }

    @Test
    void a_flight_with_no_alternate_carries_no_alternate_fuel() {
        FuelBreakdown fuel = policy.applyWithoutAlternate(10_000, 0, 0);

        assertThat(fuel.alternateKg()).isZero();
        assertThat(fuel.fuelAtAlternateKg()).isCloseTo(fuel.fuelOnLandingKg(), within(1e-6));
    }

    @Test
    void extra_and_additional_fuel_can_be_revised_without_recomputing_the_rest() {
        FuelBreakdown fuel = policy.apply(10_000, 1_800, 0, 0);

        assertThat(fuel.withExtra(500).takeoffFuelKg())
                .isCloseTo(fuel.takeoffFuelKg() + 500.0, within(1e-6));
        assertThat(fuel.withAdditional(750).minimumTakeoffFuelKg())
                .isCloseTo(fuel.minimumTakeoffFuelKg() + 750.0, within(1e-6));
    }

    @Test
    void the_OFP_rows_come_out_in_the_order_they_are_printed() {
        assertThat(policy.apply(10_000, 1_800, 0, 0).asOfpRows().keySet())
                .containsExactly("TRIP", "CONT", "ALTN", "FINRES", "ADDL", "EXTRA",
                        "TAKEOFF", "TAXI", "RAMP");
    }

    @Test
    void refuses_a_contingency_that_is_not_a_fraction() {
        assertThatThrownBy(() -> new FuelPolicy(1.5, 5, 30, 2_400, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    void refuses_negative_figures() {
        assertThatThrownBy(() -> new FuelPolicy(0.05, 5, -30, 2_400, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void the_weight_limits_bind_together_on_the_takeoff_weight() {
        WeightLimits limits = AircraftTypes.narrowbodyTwin().weightLimits();

        // With plenty of fuel and a long enough trip to burn 12.6 t off, the structure binds.
        assertThat(limits.maximumTakeoffWeightKg(18_000, 14_000)).isEqualTo(79_000.0);
        // With little fuel on board, the zero-fuel limit plus that fuel binds instead.
        assertThat(limits.maximumTakeoffWeightKg(3_000, 2_000)).isEqualTo(65_700.0);
        // With a short trip, the landing weight binds: nothing burns off on the way.
        assertThat(limits.maximumTakeoffWeightKg(18_000, 500)).isEqualTo(66_900.0);
    }

    @Test
    void reports_the_payload_that_fits_around_the_fuel() {
        WeightLimits limits = AircraftTypes.narrowbodyTwin().weightLimits();

        assertThat(limits.maxPayloadKg()).isEqualTo(21_200.0);
        // Tankering fuel eats into the payload once the takeoff limit binds.
        assertThat(limits.availablePayloadKg(20_000, 12_000))
                .isLessThan(limits.maxPayloadKg());
    }
}
