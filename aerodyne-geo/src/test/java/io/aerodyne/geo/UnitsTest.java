package io.aerodyne.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class UnitsTest {

    @Test
    void the_nautical_mile_and_the_foot_are_exact_by_definition() {
        assertThat(Units.nauticalMilesToMetres(1.0)).isEqualTo(1852.0);
        assertThat(Units.feetToMetres(1.0)).isEqualTo(0.3048);
    }

    @ParameterizedTest
    @CsvSource({"0.0, 15.0", "10000.0, -4.8", "36089.24, -56.5", "41000.0, -56.5"})
    void isa_temperature_lapses_to_the_tropopause_then_holds(double altitudeFt, double expectedC) {
        assertThat(Units.isaTemperatureC(altitudeFt)).isCloseTo(expectedC, within(0.1));
    }

    @Test
    void isa_deviation_is_measured_against_the_altitude_not_sea_level() {
        // -40 C at FL350 is warm: standard there is about -54.3 C.
        assertThat(Units.isaDeviationC(35_000.0, -40.0)).isCloseTo(14.3, within(0.2));
    }

    @Test
    void mach_converts_to_true_airspeed_through_the_local_speed_of_sound() {
        // M0.85 in ISA conditions at FL350.
        double tas = Units.machToTrueAirspeedKt(0.85, Units.isaTemperatureC(35_000.0));

        assertThat(tas).isCloseTo(487.0, within(3.0));
    }

    @Test
    void the_speed_of_sound_at_sea_level_is_about_661_knots() {
        assertThat(Units.speedOfSoundKt(15.0)).isCloseTo(661.5, within(0.5));
    }

    @Test
    void rejects_a_temperature_below_absolute_zero() {
        assertThatThrownBy(() -> Units.speedOfSoundKt(-300.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute zero");
    }

    @Test
    void a_standard_altimeter_setting_is_1013_hectopascals() {
        assertThat(Units.inchesHgToHectopascal(29.92)).isCloseTo(1013.2, within(0.1));
    }

    @Test
    void pressure_altitude_maps_onto_flight_levels() {
        assertThat(Units.pressureAltitudeToFlightLevel(35_000.0)).isEqualTo(350);
        assertThat(Units.flightLevelToFeet(390)).isEqualTo(39_000.0);
    }

    @Test
    void mass_round_trips_between_pounds_and_kilograms() {
        assertThat(Units.kilogramsToPounds(Units.poundsToKilograms(150_000.0)))
                .isCloseTo(150_000.0, within(1e-9));
    }
}
