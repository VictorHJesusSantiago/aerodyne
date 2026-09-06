package io.aerodyne.weather.grib;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.weather.LatLonGrid;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class Grib2DecoderTest {

    private final Grib2Decoder decoder = new Grib2Decoder();

    @Test
    void decodes_a_wind_component_message() {
        byte[] message = Grib2Writer.message()
                .parameter(0, 2, 2)
                .isobaricLevelHpa(250)
                .referenceTime(Instant.parse("2024-06-01T12:00:00Z"))
                .forecastHours(6)
                .grid(60.0, -20.0, -1.0, 1.0, 5, 4)
                .values(ramp(20))
                .build();

        Grib2Message decoded = decoder.decode(message).get(0);

        assertThat(decoded.parameter()).isEqualTo(Grib2Parameter.U_WIND);
        assertThat(decoded.isIsobaric()).isTrue();
        assertThat(decoded.pressureHpa()).isCloseTo(250.0, within(1e-6));
        assertThat(decoded.referenceTime()).isEqualTo(Instant.parse("2024-06-01T12:00:00Z"));
        assertThat(decoded.validAt()).isEqualTo(Instant.parse("2024-06-01T18:00:00Z"));
    }

    @Test
    void recovers_the_packed_values_to_the_precision_the_scale_carries() {
        float[] values = {10.25f, -3.5f, 0.0f, 42.75f, 7.125f, -18.0f};

        LatLonGrid grid = decodeGrid(Grib2Writer.message()
                .grid(50.0, 0.0, -1.0, 1.0, 3, 2)
                .values(values)
                .packing(16, 0, 3)
                .build());

        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 3; column++) {
                assertThat(grid.valueAt(column, row))
                        .isCloseTo(values[row * 3 + column], within(0.001));
            }
        }
    }

    @Test
    void reads_the_grid_geometry_including_a_negative_first_longitude() {
        LatLonGrid grid = decodeGrid(Grib2Writer.message()
                .grid(60.0, -20.0, -1.0, 1.0, 5, 4)
                .values(ramp(20))
                .build());

        assertThat(grid.columns()).isEqualTo(5);
        assertThat(grid.rows()).isEqualTo(4);
        assertThat(grid.positionAt(0, 0)).isEqualTo(GeoPoint.of(60.0, -20.0));
        // Rows scan southward, so the last row is three degrees below the first.
        assertThat(grid.positionAt(4, 3)).isEqualTo(GeoPoint.of(57.0, -16.0));
    }

    @Test
    void reads_a_grid_that_scans_northward() {
        LatLonGrid grid = decodeGrid(Grib2Writer.message()
                .grid(40.0, 10.0, 1.0, 1.0, 3, 3)
                .values(ramp(9))
                .build());

        assertThat(grid.positionAt(0, 2)).isEqualTo(GeoPoint.of(42.0, 10.0));
    }

    @Test
    void a_zero_bit_field_is_constant_at_the_reference_value() {
        float[] constant = new float[9];
        java.util.Arrays.fill(constant, 12.5f);

        LatLonGrid grid = decodeGrid(Grib2Writer.message()
                .grid(40.0, 10.0, -1.0, 1.0, 3, 3)
                .values(constant)
                .packing(0, 0, 2)
                .build());

        assertThat(grid.valueAt(1, 1)).isCloseTo(12.5, within(1e-4));
    }

    @Test
    void reads_a_binary_scale_factor() {
        LatLonGrid grid = decodeGrid(Grib2Writer.message()
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2)
                .values(new float[]{0f, 8f, 16f, 24f, 32f, 64f})
                .packing(8, 3, 0)
                .build());

        assertThat(grid.valueAt(2, 1)).isCloseTo(64.0, within(1e-3));
    }

    @Test
    void reads_a_forecast_time_given_in_minutes() {
        Grib2Message decoded = decoder.decode(Grib2Writer.message()
                .referenceTime(Instant.parse("2024-06-01T00:00:00Z"))
                .forecastTime(0, 90)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2)
                .values(ramp(6))
                .build()).get(0);

        assertThat(decoded.validAt()).isEqualTo(Instant.parse("2024-06-01T01:30:00Z"));
    }

    @Test
    void decodes_several_messages_concatenated_into_one_file() {
        byte[] file = concat(
                Grib2Writer.message().parameter(0, 2, 2).isobaricLevelHpa(300)
                        .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build(),
                Grib2Writer.message().parameter(0, 2, 3).isobaricLevelHpa(300)
                        .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build(),
                Grib2Writer.message().parameter(0, 0, 0).isobaricLevelHpa(300)
                        .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build());

        assertThat(decoder.decode(file))
                .extracting(Grib2Message::parameter)
                .containsExactly(Grib2Parameter.U_WIND, Grib2Parameter.V_WIND,
                        Grib2Parameter.TEMPERATURE);
    }

    @Test
    void skips_a_field_it_does_not_need_and_carries_on_to_the_next_message() {
        byte[] file = concat(
                // Relative humidity: a real field in a real file, and not one a plan wants.
                Grib2Writer.message().parameter(0, 1, 1).isobaricLevelHpa(300)
                        .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build(),
                Grib2Writer.message().parameter(0, 2, 2).isobaricLevelHpa(300)
                        .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build());

        assertThat(decoder.decode(file))
                .singleElement()
                .extracting(Grib2Message::parameter)
                .isEqualTo(Grib2Parameter.U_WIND);
    }

    @Test
    void skips_a_wind_field_that_is_not_on_an_isobaric_surface() {
        // Type 103 is a height above ground: the ten-metre surface wind, not an upper wind.
        byte[] message = Grib2Writer.message()
                .parameter(0, 2, 2).levelType(103)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6))
                .build();

        assertThat(decoder.decode(message)).isEmpty();
    }

    @Test
    void refuses_an_unsupported_grid_projection_rather_than_misreading_it() {
        byte[] message = Grib2Writer.message().gridTemplate(30)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("3.30")
                .hasMessageContaining("latitude/longitude");
    }

    @Test
    void refuses_a_packing_scheme_it_cannot_unpack() {
        // Template 5.40 is JPEG 2000, which several centres use for their global output.
        byte[] message = Grib2Writer.message().dataTemplate(40)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("5.40")
                .hasMessageContaining("simple packing");
    }

    @Test
    void refuses_a_message_carrying_a_bitmap_of_missing_values() {
        byte[] message = Grib2Writer.message().bitmapIndicator(0)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("bitmap");
    }

    @Test
    void accepts_a_message_whose_bitmap_section_says_no_bitmap_applies() {
        byte[] message = Grib2Writer.message().bitmapIndicator(255)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();

        assertThat(decoder.decode(message)).hasSize(1);
    }

    @Test
    void refuses_an_edition_it_does_not_understand() {
        byte[] message = Grib2Writer.message().edition(1)
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("edition 1");
    }

    @Test
    void refuses_a_message_that_does_not_end_where_it_says_it_does() {
        byte[] message = Grib2Writer.message()
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();
        message[message.length - 1] = 'X';

        assertThatThrownBy(() -> decoder.decode(message))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("7777");
    }

    @Test
    void refuses_a_truncated_file_rather_than_decoding_what_it_can() {
        byte[] message = Grib2Writer.message()
                .grid(40.0, 10.0, -1.0, 1.0, 3, 2).values(ramp(6)).build();
        byte[] truncated = java.util.Arrays.copyOf(message, message.length - 10);

        assertThatThrownBy(() -> decoder.decode(truncated))
                .isInstanceOf(Grib2Exception.class)
                .hasMessageContaining("bytes remain");
    }

    @Test
    void an_empty_stream_yields_no_messages() {
        assertThat(decoder.decode(new byte[0])).isEmpty();
    }

    private LatLonGrid decodeGrid(byte[] message) {
        List<Grib2Message> messages = decoder.decode(message);
        assertThat(messages).as("the message should have decoded").hasSize(1);
        return messages.get(0).grid();
    }

    private static float[] ramp(int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = i;
        }
        return values;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
