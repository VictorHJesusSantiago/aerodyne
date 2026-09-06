package io.aerodyne.weather;

import io.aerodyne.geo.Units;

/**
 * The ICAO Standard Atmosphere, used here to move between the two vertical coordinates flight
 * planning has to reconcile.
 *
 * <p>Upper-air forecasts are published on isobaric surfaces — the 250 hPa level, the 300 hPa
 * level — because that is how the atmosphere is modelled. Aircraft fly pressure altitudes, and
 * a flight level <em>is</em> a pressure surface: FL350 is wherever the altimeter reads 35 000 ft
 * against a 1013.25 hPa datum. So the conversion between the two is exact by definition rather
 * than an approximation of the real atmosphere, and no forecast temperature enters it.
 */
public final class StandardAtmosphere {

    /** Sea-level pressure of the standard atmosphere, hPa. */
    public static final double SEA_LEVEL_PRESSURE_HPA = 1013.25;
    /** Sea-level temperature, kelvin. */
    public static final double SEA_LEVEL_TEMPERATURE_K = 288.15;
    /** Temperature lapse rate below the tropopause, kelvin per metre. */
    public static final double LAPSE_RATE_K_PER_M = 0.0065;
    /** Geopotential altitude of the tropopause, metres. */
    public static final double TROPOPAUSE_ALTITUDE_M = 11_000.0;
    /** Pressure at the tropopause, hPa. */
    public static final double TROPOPAUSE_PRESSURE_HPA = 226.32;
    /** Temperature at and above the tropopause, kelvin. */
    public static final double TROPOPAUSE_TEMPERATURE_K = 216.65;

    /** Sea-level air density of the standard atmosphere, kg/m³. */
    public static final double SEA_LEVEL_DENSITY = 1.225;

    /** Specific gas constant for dry air, J/(kg K). */
    private static final double GAS_CONSTANT = 287.05287;
    /** Standard gravity, m/s². */
    private static final double GRAVITY = 9.80665;
    /** The troposphere exponent, {@code R L / g}. */
    private static final double TROPOSPHERE_EXPONENT = GAS_CONSTANT * LAPSE_RATE_K_PER_M / GRAVITY;
    /** Scale height in the isothermal layer above the tropopause, metres. */
    private static final double STRATOSPHERE_SCALE_HEIGHT_M =
            GAS_CONSTANT * TROPOPAUSE_TEMPERATURE_K / GRAVITY;

    private StandardAtmosphere() {
    }

    /** The pressure altitude, in feet, of an isobaric surface. */
    public static double pressureAltitudeFt(double pressureHpa) {
        return Units.metresToFeet(pressureAltitudeMetres(pressureHpa));
    }

    /** The pressure altitude, in metres, of an isobaric surface. */
    public static double pressureAltitudeMetres(double pressureHpa) {
        if (pressureHpa <= 0.0) {
            throw new IllegalArgumentException("pressure must be positive: " + pressureHpa);
        }
        if (pressureHpa >= TROPOPAUSE_PRESSURE_HPA) {
            double ratio = Math.pow(pressureHpa / SEA_LEVEL_PRESSURE_HPA, TROPOSPHERE_EXPONENT);
            return (SEA_LEVEL_TEMPERATURE_K / LAPSE_RATE_K_PER_M) * (1.0 - ratio);
        }
        // Isothermal above the tropopause, so pressure decays exponentially with height.
        return TROPOPAUSE_ALTITUDE_M
                + STRATOSPHERE_SCALE_HEIGHT_M * Math.log(TROPOPAUSE_PRESSURE_HPA / pressureHpa);
    }

    /** The pressure, in hPa, at a pressure altitude given in feet. */
    public static double pressureHpa(double pressureAltitudeFt) {
        return pressureHpaAtMetres(Units.feetToMetres(pressureAltitudeFt));
    }

    /** The pressure, in hPa, at a pressure altitude given in metres. */
    public static double pressureHpaAtMetres(double altitudeMetres) {
        if (altitudeMetres <= TROPOPAUSE_ALTITUDE_M) {
            double temperatureRatio =
                    1.0 - LAPSE_RATE_K_PER_M * altitudeMetres / SEA_LEVEL_TEMPERATURE_K;
            return SEA_LEVEL_PRESSURE_HPA * Math.pow(temperatureRatio, 1.0 / TROPOSPHERE_EXPONENT);
        }
        return TROPOPAUSE_PRESSURE_HPA * Math.exp(
                -(altitudeMetres - TROPOPAUSE_ALTITUDE_M) / STRATOSPHERE_SCALE_HEIGHT_M);
    }

    /** Air density at a pressure altitude in standard conditions, kg/m³. */
    public static double densityKgPerM3(double pressureAltitudeFt, double temperatureC) {
        double pressurePa = pressureHpa(pressureAltitudeFt) * 100.0;
        return pressurePa / (GAS_CONSTANT * Units.celsiusToKelvin(temperatureC));
    }

    /**
     * The altitude at which the standard atmosphere has the same density as the real one here.
     * Takeoff and climb performance are read against this, not against pressure altitude.
     */
    public static double densityAltitudeFt(double pressureAltitudeFt, double temperatureC) {
        double densityRatio = densityKgPerM3(pressureAltitudeFt, temperatureC) / SEA_LEVEL_DENSITY;
        // In the troposphere the density ratio is (1 - Lh/T0) raised to g/(RL) - 1, so
        // inverting it is a matter of taking that power's root.
        double exponent = 1.0 / TROPOSPHERE_EXPONENT - 1.0;
        double altitudeM = (SEA_LEVEL_TEMPERATURE_K / LAPSE_RATE_K_PER_M)
                * (1.0 - Math.pow(densityRatio, 1.0 / exponent));
        return Units.metresToFeet(altitudeM);
    }
}
