package io.aerodyne.performance;

import io.aerodyne.weather.AtmosphericConditions;

/**
 * How the aircraft cruises: what Mach number it holds and what it burns doing so.
 *
 * <p>Both come from tables read against gross weight and pressure altitude, with a correction
 * for temperature. Temperature matters twice over and in opposite directions: a warm day gives
 * a higher true airspeed for the same Mach number, and it costs fuel flow. The Mach table
 * handles the first implicitly — Mach is what the aircraft flies, and the true airspeed follows
 * from the temperature — and {@code isaFuelFactorPerDegreeC} handles the second.
 *
 * @param machTable             cruise Mach number against weight and altitude
 * @param fuelFlowTable         fuel flow in kilograms per hour, per engine-set, in ISA conditions
 * @param isaFuelFactorPerDegreeC fractional change in fuel flow per degree of ISA deviation;
 *                              a typical turbofan burns rather under half a percent more per degree
 * @param optimumAltitudeTable  the altitude giving the best specific range at a weight; the
 *                              table is one-dimensional in weight, so its altitude axis is a single point
 */
public record CruisePerformance(PerformanceTable machTable,
                                PerformanceTable fuelFlowTable,
                                double isaFuelFactorPerDegreeC,
                                PerformanceTable optimumAltitudeTable) {

    /** The Mach number the aircraft holds at this weight and altitude. */
    public double machAt(double weightKg, double altitudeFt) {
        return machTable.valueAt(weightKg, altitudeFt);
    }

    /** True airspeed in knots, which is the Mach number resolved against the real temperature. */
    public double trueAirspeedKt(double weightKg, double altitudeFt, AtmosphericConditions air) {
        return air.trueAirspeedKt(machAt(weightKg, altitudeFt));
    }

    /**
     * Fuel flow in kilograms per hour, corrected for how far the air departs from standard.
     *
     * <p>The correction is applied to the tabulated ISA value rather than being tabulated
     * itself, because a third axis on the table would be mostly interpolation between two
     * nearly-linear points.
     */
    public double fuelFlowKgPerHour(double weightKg, double altitudeFt, AtmosphericConditions air) {
        double isaFlow = fuelFlowTable.valueAt(weightKg, altitudeFt);
        double deviation = air.isaDeviationC(altitudeFt);
        return isaFlow * (1.0 + isaFuelFactorPerDegreeC * deviation);
    }

    /**
     * Specific range: nautical miles per kilogram of fuel, in still air. This is what the
     * optimum altitude maximises, and what a step climb is worth measuring against.
     */
    public double specificRangeNmPerKg(double weightKg, double altitudeFt, AtmosphericConditions air) {
        double fuelFlow = fuelFlowKgPerHour(weightKg, altitudeFt, air);
        if (fuelFlow <= 0.0) {
            throw new IllegalStateException("fuel flow table returned " + fuelFlow + " kg/h");
        }
        return trueAirspeedKt(weightKg, altitudeFt, air) / fuelFlow;
    }

    /** The altitude giving the best specific range at a weight, before ATC constraints. */
    public double optimumAltitudeFt(double weightKg) {
        return optimumAltitudeTable.valueAt(weightKg, optimumAltitudeTable.minimumAltitudeFt());
    }
}
