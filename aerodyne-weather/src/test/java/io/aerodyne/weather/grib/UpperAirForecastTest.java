package io.aerodyne.weather.grib;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.weather.AtmosphericConditions;
import io.aerodyne.weather.GriddedWeatherModel;
import io.aerodyne.weather.StandardAtmosphere;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The whole weather path end to end: GRIB2 bytes in, a model that answers "what is the wind at
 * this point and level" out.
 */
class UpperAirForecastTest {

    private static final Instant REFERENCE = Instant.parse("2024-06-01T00:00:00Z");
    private static final GeoPoint OVER_THE_ATLANTIC = GeoPoint.of(50.0, -30.0);

    /** A 50 m/s westerly at 250 hPa: about 97 kt from 270. */
    private byte[] windPair(double pressureHpa, float eastwardMps, float northwardMps, int forecastHours) {
        return concat(
                field(0, 2, 2, pressureHpa, eastwardMps, forecastHours),
                field(0, 2, 3, pressureHpa, northwardMps, forecastHours));
    }

    private byte[] field(int discipline, int category, int number,
                         double pressureHpa, float value, int forecastHours) {
        float[] values = new float[12];
        java.util.Arrays.fill(values, value);
        return Grib2Writer.message()
                .parameter(discipline, category, number)
                .isobaricLevelHpa(pressureHpa)
                .referenceTime(REFERENCE)
                .forecastHours(forecastHours)
                .grid(55.0, -40.0, -5.0, 10.0, 4, 3)
                .values(values)
                .packing(16, 0, 2)
                .build();
    }

    @Test
    void assembles_a_model_from_a_pair_of_wind_components() {
        GriddedWeatherModel model = UpperAirForecast.assemble(
                new Grib2Decoder().decode(windPair(250, 50.0f, 0.0f, 0)), "test.grib2");

        AtmosphericConditions conditions = model.at(OVER_THE_ATLANTIC,
                StandardAtmosphere.pressureAltitudeFt(250), REFERENCE);

        // 50 m/s eastward is a wind blowing towards 090, so reported as from 270.
        assertThat(conditions.wind().directionDeg()).isCloseTo(270.0, within(0.5));
        assertThat(conditions.wind().speedKt()).isCloseTo(97.2, within(0.5));
    }

    @Test
    void converts_metres_per_second_and_kelvin_once_on_the_way_in() {
        byte[] file = concat(
                windPair(250, 0.0f, 25.0f, 0),
                field(0, 0, 0, 250, 223.15f, 0));

        AtmosphericConditions conditions = UpperAirForecast
                .assemble(new Grib2Decoder().decode(file), "test.grib2")
                .at(OVER_THE_ATLANTIC, StandardAtmosphere.pressureAltitudeFt(250), REFERENCE);

        // 25 m/s northward: a southerly of about 48.6 kt.
        assertThat(conditions.wind().directionDeg()).isCloseTo(180.0, within(0.5));
        assertThat(conditions.wind().speedKt()).isCloseTo(48.6, within(0.5));
        assertThat(conditions.temperatureC()).isCloseTo(-50.0, within(0.5));
    }

    @Test
    void falls_back_to_the_standard_atmosphere_for_a_winds_only_file() {
        AtmosphericConditions conditions = UpperAirForecast
                .assemble(new Grib2Decoder().decode(windPair(250, 50.0f, 0.0f, 0)), "test.grib2")
                .at(OVER_THE_ATLANTIC, StandardAtmosphere.pressureAltitudeFt(250), REFERENCE);

        assertThat(conditions.isaDeviationC(StandardAtmosphere.pressureAltitudeFt(250)))
                .isCloseTo(0.0, within(0.5));
    }

    @Test
    void stacks_the_levels_a_file_holds() {
        byte[] file = concat(
                windPair(500, 20.0f, 0.0f, 0),
                windPair(250, 60.0f, 0.0f, 0));

        GriddedWeatherModel model =
                UpperAirForecast.assemble(new Grib2Decoder().decode(file), "test.grib2");

        assertThat(model.pressureLevelsHpa()).containsExactly(500.0, 250.0);
        assertThat(model.at(OVER_THE_ATLANTIC, StandardAtmosphere.pressureAltitudeFt(500), REFERENCE)
                .wind().speedKt()).isCloseTo(38.9, within(0.5));
        assertThat(model.at(OVER_THE_ATLANTIC, StandardAtmosphere.pressureAltitudeFt(250), REFERENCE)
                .wind().speedKt()).isCloseTo(116.6, within(0.5));
    }

    @Test
    void orders_the_forecast_hours_whatever_order_the_file_holds_them_in() {
        byte[] file = concat(
                windPair(250, 60.0f, 0.0f, 12),
                windPair(250, 20.0f, 0.0f, 0));

        GriddedWeatherModel model =
                UpperAirForecast.assemble(new Grib2Decoder().decode(file), "test.grib2");

        assertThat(model.validTimes()).containsExactly(REFERENCE, REFERENCE.plusSeconds(12 * 3600));
        assertThat(model.issuedAt()).isEqualTo(REFERENCE);
    }

    @Test
    void drops_a_level_that_has_only_one_wind_component() {
        byte[] file = concat(
                field(0, 2, 2, 250, 50.0f, 0),
                windPair(500, 20.0f, 0.0f, 0));

        GriddedWeatherModel model =
                UpperAirForecast.assemble(new Grib2Decoder().decode(file), "test.grib2");

        assertThat(model.pressureLevelsHpa()).containsExactly(500.0);
    }

    @Test
    void refuses_a_file_with_no_complete_wind_level_in_it() {
        byte[] file = field(0, 2, 2, 250, 50.0f, 0);

        assertThatThrownBy(() ->
                UpperAirForecast.assemble(new Grib2Decoder().decode(file), "test.grib2"))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("no complete wind level");
    }

    @Test
    void refuses_an_empty_decode() {
        assertThatThrownBy(() -> UpperAirForecast.assemble(java.util.List.of(), "empty.grib2"))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("no usable upper-air fields");
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Test
    void a_forecast_can_be_loaded_from_bytes_rather_than_from_a_path() {
        GriddedWeatherModel model =
                UpperAirForecast.load(windPair(250, 50.0f, 0.0f, 0), "uploaded.grib2");

        assertThat(model.source()).contains("uploaded.grib2");
        assertThat(model.pressureLevelsHpa()).containsExactly(250.0);
    }
}
