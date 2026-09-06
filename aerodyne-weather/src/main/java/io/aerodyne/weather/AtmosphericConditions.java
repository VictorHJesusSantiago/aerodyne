package io.aerodyne.weather;

import io.aerodyne.geo.Units;

/**
 * The state of the atmosphere at one point and altitude: what the fuel integration needs to
 * turn a leg distance into a time and a burn.
 *
 * @param wind          the wind
 * @param temperatureC  static air temperature
 */
public record AtmosphericConditions(WindVector wind, double temperatureC) {

    /** Standard atmosphere at an altitude, with no wind. */
    public static AtmosphericConditions isa(double pressureAltitudeFt) {
        return new AtmosphericConditions(WindVector.CALM, Units.isaTemperatureC(pressureAltitudeFt));
    }

    /** How far this temperature departs from the standard atmosphere at the same altitude. */
    public double isaDeviationC(double pressureAltitudeFt) {
        return Units.isaDeviationC(pressureAltitudeFt, temperatureC);
    }

    /** True airspeed for a Mach number flown in these conditions. */
    public double trueAirspeedKt(double mach) {
        return Units.machToTrueAirspeedKt(mach, temperatureC);
    }
}
