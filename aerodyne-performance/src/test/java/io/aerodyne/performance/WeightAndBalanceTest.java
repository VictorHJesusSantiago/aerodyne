package io.aerodyne.performance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WeightAndBalanceTest {

    private final AircraftType type = AircraftTypes.narrowbodyTwin();

    /** A loading that sits comfortably inside every limit. */
    private WeightAndBalance nominal() {
        return WeightAndBalance.forType(type)
                .load("Forward cabin", 5_000, 14.0)
                .load("Aft cabin", 5_000, 22.0)
                .load("Forward hold", 1_500, 12.0)
                .load("Aft hold", 1_500, 26.0)
                .fuel(9_000, 17.9)
                .tripFuel(5_000);
    }

    @Test
    void adds_the_empty_weight_the_payload_and_the_fuel() {
        Loadsheet sheet = nominal().compute();

        assertThat(sheet.zeroFuelWeightKg()).isEqualTo(41_500 + 13_000);
        assertThat(sheet.takeoffWeightKg()).isEqualTo(41_500 + 13_000 + 9_000);
        assertThat(sheet.landingWeightKg()).isEqualTo(41_500 + 13_000 + 4_000);
        assertThat(sheet.payloadKg()).isEqualTo(13_000.0);
        assertThat(sheet.takeoffFuelKg()).isEqualTo(9_000.0);
    }

    @Test
    void locates_the_centre_of_gravity_by_moments() {
        Loadsheet sheet = nominal().compute();

        // The empty aircraft sits at 17.88 m, which is 21.1 % of a 4.17 m chord starting at 17.0 m.
        double emptyPercentMac = type.armToPercentMac(type.operatingEmptyArmMetres());
        assertThat(emptyPercentMac).isCloseTo(21.1, within(0.1));
        // A symmetrical payload about the empty centre of gravity barely moves it.
        assertThat(sheet.zeroFuelCgPercentMac()).isBetween(15.0, 30.0);
    }

    @Test
    void moving_load_aft_moves_the_centre_of_gravity_aft() {
        Loadsheet forward = WeightAndBalance.forType(type)
                .load("Payload", 13_000, 14.0).fuel(9_000, 17.9).tripFuel(5_000).compute();
        Loadsheet aft = WeightAndBalance.forType(type)
                .load("Payload", 13_000, 22.0).fuel(9_000, 17.9).tripFuel(5_000).compute();

        assertThat(aft.takeoffCgPercentMac()).isGreaterThan(forward.takeoffCgPercentMac());
        assertThat(forward.takeoffWeightKg()).isEqualTo(aft.takeoffWeightKg());
    }

    @Test
    void the_conversion_between_arm_and_percent_MAC_round_trips() {
        assertThat(type.percentMacToArm(type.armToPercentMac(19.5)))
                .isCloseTo(19.5, within(1e-9));
    }

    @Test
    void a_loading_within_every_limit_is_legal() {
        Loadsheet sheet = nominal().compute();

        assertThat(sheet.exceedances()).isEmpty();
        assertThat(sheet.isWithinLimits()).isTrue();
        assertThat(sheet.takeoffCgMarginPercentMac()).isPositive();
    }

    @Test
    void reports_a_zero_fuel_weight_over_the_limit() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load("Payload", 25_000, 18.0)
                .fuel(9_000, 17.9)
                .tripFuel(5_000)
                .compute();

        assertThat(sheet.isWithinLimits()).isFalse();
        assertThat(sheet.exceedances())
                .anyMatch(e -> e.contains("zero-fuel weight") && e.contains("exceeds"));
    }

    @Test
    void reports_a_takeoff_weight_over_the_limit() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load("Payload", 21_000, 18.0)
                .fuel(18_000, 17.9)
                .tripFuel(5_000)
                .compute();

        assertThat(sheet.exceedances()).anyMatch(e -> e.contains("takeoff weight"));
    }

    @Test
    void reports_a_landing_weight_over_the_limit_when_too_little_burns_off() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load("Payload", 20_000, 18.0)
                .fuel(17_000, 17.9)
                .tripFuel(500)
                .compute();

        assertThat(sheet.exceedances()).anyMatch(e -> e.contains("landing weight"));
    }

    @Test
    void reports_a_centre_of_gravity_outside_the_envelope_with_the_limits_that_applied() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load("All the way forward", 13_000, 5.0)
                .fuel(9_000, 17.9)
                .tripFuel(5_000)
                .compute();

        assertThat(sheet.exceedances())
                .anyMatch(e -> e.contains("centre of gravity") && e.contains("limits"));
        assertThat(sheet.takeoffCgMarginPercentMac()).isNegative();
    }

    @Test
    void reports_fuel_beyond_the_tank_capacity() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load("Payload", 5_000, 18.0)
                .fuel(25_000, 17.9)
                .tripFuel(5_000)
                .compute();

        assertThat(sheet.exceedances()).anyMatch(e -> e.contains("fuel on board"));
    }

    @Test
    void reports_how_much_more_could_have_been_carried() {
        Loadsheet sheet = nominal().compute();

        assertThat(sheet.spareCapacityKg()).isPositive();
        assertThat(sheet.spareCapacityKg())
                .isLessThanOrEqualTo(type.weightLimits().maxZeroFuelKg() - sheet.zeroFuelWeightKg());
    }

    @Test
    void keeps_the_stations_that_went_into_the_sheet() {
        Loadsheet sheet = nominal().compute();

        assertThat(sheet.stations())
                .extracting(LoadStation::name)
                .startsWith("Operating empty weight")
                .endsWith("Fuel");
    }

    @Test
    void a_station_cannot_weigh_less_than_nothing() {
        assertThatThrownBy(() -> new LoadStation("Impossible", -1, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative mass");
    }

    @Test
    void accepts_a_list_of_stations_at_once() {
        Loadsheet sheet = WeightAndBalance.forType(type)
                .load(List.of(new LoadStation("Zone A", 6_000, 15.0),
                        new LoadStation("Zone B", 6_000, 21.0)))
                .fuel(9_000, 17.9)
                .tripFuel(5_000)
                .compute();

        assertThat(sheet.payloadKg()).isEqualTo(12_000.0);
    }
}
