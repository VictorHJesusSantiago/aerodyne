package io.aerodyne.geo;

/**
 * Conversions between the unit systems a flight plan mixes: distances in nautical miles,
 * altitudes in feet, speeds in knots, fuel in kilograms or pounds, temperatures in Celsius.
 */
public final class Units {

    /** The international nautical mile, exactly 1852 m by definition. */
    public static final double METRES_PER_NAUTICAL_MILE = 1852.0;
    /** The international foot, exactly 0.3048 m. */
    public static final double METRES_PER_FOOT = 0.3048;
    public static final double METRES_PER_STATUTE_MILE = 1609.344;
    public static final double KILOGRAMS_PER_POUND = 0.45359237;
    /** ISA sea-level temperature. */
    public static final double ISA_SEA_LEVEL_TEMP_C = 15.0;
    /** ISA lapse rate below the tropopause, degrees Celsius per foot. */
    public static final double ISA_LAPSE_RATE_C_PER_FOOT = 0.0019812;
    /** ISA tropopause altitude in feet; the standard atmosphere is isothermal above it. */
    public static final double ISA_TROPOPAUSE_FT = 36_089.24;
    /** ISA temperature above the tropopause. */
    public static final double ISA_TROPOPAUSE_TEMP_C = -56.5;

    private Units() {
    }

    public static double metresToNauticalMiles(double metres) {
        return metres / METRES_PER_NAUTICAL_MILE;
    }

    public static double nauticalMilesToMetres(double nm) {
        return nm * METRES_PER_NAUTICAL_MILE;
    }

    public static double metresToFeet(double metres) {
        return metres / METRES_PER_FOOT;
    }

    public static double feetToMetres(double feet) {
        return feet * METRES_PER_FOOT;
    }

    public static double statuteMilesToNauticalMiles(double sm) {
        return sm * METRES_PER_STATUTE_MILE / METRES_PER_NAUTICAL_MILE;
    }

    public static double poundsToKilograms(double pounds) {
        return pounds * KILOGRAMS_PER_POUND;
    }

    public static double kilogramsToPounds(double kilograms) {
        return kilograms / KILOGRAMS_PER_POUND;
    }

    /** Metres per second to knots. */
    public static double mpsToKnots(double mps) {
        return mps * 3600.0 / METRES_PER_NAUTICAL_MILE;
    }

    public static double knotsToMps(double knots) {
        return knots * METRES_PER_NAUTICAL_MILE / 3600.0;
    }

    public static double kelvinToCelsius(double kelvin) {
        return kelvin - 273.15;
    }

    public static double celsiusToKelvin(double celsius) {
        return celsius + 273.15;
    }

    /** Pascals to hectopascals, the unit METAR reports as {@code Qxxxx}. */
    public static double pascalToHectopascal(double pascal) {
        return pascal / 100.0;
    }

    public static double inchesHgToHectopascal(double inHg) {
        return inHg * 33.8638866667;
    }

    /** Standard-atmosphere temperature at a pressure altitude, in degrees Celsius. */
    public static double isaTemperatureC(double pressureAltitudeFt) {
        if (pressureAltitudeFt >= ISA_TROPOPAUSE_FT) {
            return ISA_TROPOPAUSE_TEMP_C;
        }
        return ISA_SEA_LEVEL_TEMP_C - pressureAltitudeFt * ISA_LAPSE_RATE_C_PER_FOOT;
    }

    /**
     * Deviation of an observed temperature from the standard atmosphere at the same altitude.
     * Aircraft performance tables are indexed on this ("ISA+10"), not on absolute temperature.
     */
    public static double isaDeviationC(double pressureAltitudeFt, double observedTempC) {
        return observedTempC - isaTemperatureC(pressureAltitudeFt);
    }

    /**
     * Speed of sound at a given static air temperature, in knots. Mach number times this is
     * the true airspeed.
     */
    public static double speedOfSoundKt(double staticAirTempC) {
        double kelvin = celsiusToKelvin(staticAirTempC);
        if (kelvin <= 0.0) {
            throw new IllegalArgumentException("temperature below absolute zero: " + staticAirTempC);
        }
        return 38.967854 * Math.sqrt(kelvin);
    }

    /** True airspeed in knots for a Mach number flown at a given static air temperature. */
    public static double machToTrueAirspeedKt(double mach, double staticAirTempC) {
        return mach * speedOfSoundKt(staticAirTempC);
    }

    /** The flight level a pressure altitude corresponds to, e.g. 35000 ft becomes FL350. */
    public static int pressureAltitudeToFlightLevel(double pressureAltitudeFt) {
        return (int) Math.round(pressureAltitudeFt / 100.0);
    }

    public static double flightLevelToFeet(int flightLevel) {
        return flightLevel * 100.0;
    }
}
