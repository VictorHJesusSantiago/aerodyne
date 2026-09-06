package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Units;

import java.time.Instant;

/**
 * A uniform wind and a fixed ISA deviation everywhere.
 *
 * <p>This is what a plan falls back to when no forecast is loaded, and what the performance
 * tests use so that a fuel figure can be checked by hand.
 */
public record ConstantWeatherModel(WindVector wind, double isaDeviationC, Instant issuedAt)
        implements WeatherModel {

    /** The standard atmosphere, no wind. */
    public static ConstantWeatherModel isa() {
        return new ConstantWeatherModel(WindVector.CALM, 0.0, Instant.EPOCH);
    }

    public static ConstantWeatherModel of(WindVector wind) {
        return new ConstantWeatherModel(wind, 0.0, Instant.EPOCH);
    }

    @Override
    public AtmosphericConditions at(GeoPoint position, double pressureAltitudeFt, Instant validAt) {
        return new AtmosphericConditions(wind,
                Units.isaTemperatureC(pressureAltitudeFt) + isaDeviationC);
    }

    @Override
    public boolean covers(GeoPoint position, Instant validAt) {
        return true;
    }

    @Override
    public double strongestWindKt() {
        return wind.speedKt();
    }

    @Override
    public String source() {
        return wind.equals(WindVector.CALM) && isaDeviationC == 0.0
                ? "ISA, no wind"
                : "uniform " + wind + ", ISA" + (isaDeviationC >= 0 ? "+" : "") + Math.round(isaDeviationC);
    }
}
