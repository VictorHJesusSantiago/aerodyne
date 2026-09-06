package io.aerodyne.weather.grib;

import io.aerodyne.geo.Units;
import io.aerodyne.weather.GriddedWeatherModel;
import io.aerodyne.weather.LatLonGrid;
import io.aerodyne.weather.UpperAirLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns decoded GRIB2 messages into a {@link GriddedWeatherModel}.
 *
 * <p>The decoder emits one message per field per level per valid time, in whatever order the
 * file happens to hold them. This pairs the {@code UGRD} and {@code VGRD} of each level, picks
 * up the temperature when the file carries one, stacks the levels and orders the times.
 *
 * <p>It also does the unit conversion, once, here: GRIB publishes winds in metres per second
 * and temperatures in kelvin, and everything downstream works in knots and Celsius. Converting
 * the whole grid up front costs one pass and removes the conversion from the interpolation
 * path, where getting it wrong would be much harder to see.
 *
 * <p>A level with only one wind component is dropped with a warning: half a wind is not a wind.
 */
public final class UpperAirForecast {

    private static final Logger log = LoggerFactory.getLogger(UpperAirForecast.class);

    private UpperAirForecast() {
    }

    /** Decodes a GRIB2 file and assembles a weather model from it. */
    public static GriddedWeatherModel load(Path file) {
        return assemble(new Grib2Decoder().decode(file), file.getFileName().toString());
    }

    /**
     * The same, from the bytes of a file rather than a path to one. This is what an upload
     * arrives as, and it keeps the caller from having to spool it to disk first.
     *
     * @param data the GRIB2 message stream
     * @param name what to call it in the OFP header, usually the uploaded file's name
     */
    public static GriddedWeatherModel load(byte[] data, String name) {
        return assemble(new Grib2Decoder().decode(data), name);
    }

    /** Assembles a weather model from already-decoded messages. */
    public static GriddedWeatherModel assemble(List<Grib2Message> messages, String source) {
        if (messages.isEmpty()) {
            throw new Grib2Exception("no usable upper-air fields in " + source);
        }
        // Valid time, then pressure: the two keys that identify one level of one forecast hour.
        Map<Instant, Map<Double, LevelFields>> byTimeAndLevel = new LinkedHashMap<>();
        Instant referenceTime = messages.get(0).referenceTime();

        for (Grib2Message message : messages) {
            if (!message.isIsobaric()) {
                continue;
            }
            LevelFields fields = byTimeAndLevel
                    .computeIfAbsent(message.validAt(), t -> new LinkedHashMap<>())
                    .computeIfAbsent(message.pressureHpa(), p -> new LevelFields());
            switch (message.parameter()) {
                case U_WIND -> fields.eastward = toKnots(message.grid());
                case V_WIND -> fields.northward = toKnots(message.grid());
                case TEMPERATURE -> fields.temperature = toCelsius(message.grid());
                case OTHER -> { /* the decoder does not emit these */ }
            }
        }

        GriddedWeatherModel.Builder builder = GriddedWeatherModel.builder()
                .issuedAt(referenceTime)
                .source(source);
        int hoursAdded = 0;
        for (Map.Entry<Instant, Map<Double, LevelFields>> hour : byTimeAndLevel.entrySet()) {
            List<UpperAirLevel> levels = new ArrayList<>();
            hour.getValue().forEach((pressureHpa, fields) -> {
                if (fields.eastward == null || fields.northward == null) {
                    log.warn("dropping the {} hPa level valid {}: only one wind component present",
                            Math.round(pressureHpa), hour.getKey());
                    return;
                }
                levels.add(new UpperAirLevel(pressureHpa, fields.eastward, fields.northward,
                        fields.temperature));
            });
            if (levels.isEmpty()) {
                log.warn("dropping the forecast valid {}: no complete level in it", hour.getKey());
                continue;
            }
            builder.hour(hour.getKey(), levels);
            hoursAdded++;
        }
        if (hoursAdded == 0) {
            throw new Grib2Exception("no complete wind level in " + source);
        }
        return builder.build();
    }

    private static LatLonGrid toKnots(LatLonGrid metresPerSecond) {
        return metresPerSecond.transformed(Units::mpsToKnots);
    }

    private static LatLonGrid toCelsius(LatLonGrid kelvin) {
        return kelvin.transformed(Units::kelvinToCelsius);
    }

    /** The three fields that make up one level, filled in as the messages arrive. */
    private static final class LevelFields {
        private LatLonGrid eastward;
        private LatLonGrid northward;
        private LatLonGrid temperature;
    }
}
