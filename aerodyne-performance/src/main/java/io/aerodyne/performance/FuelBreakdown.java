package io.aerodyne.performance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fuel figures that go on an OFP, each with its own reason for existing.
 *
 * <p>Two totals matter and they differ by the taxi fuel: the <em>ramp</em> fuel is what goes in
 * the tanks, and the <em>takeoff</em> fuel is what is left when the aircraft leaves the ground.
 * The weight limits apply to the second, the fuel order to the first.
 *
 * @param taxiKg         burned between off-blocks and takeoff
 * @param tripKg         takeoff to landing at the destination
 * @param contingencyKg  for what the plan could not know
 * @param alternateKg    missed approach, transit and landing at the alternate
 * @param finalReserveKg holding fuel that may not be planned into
 * @param additionalKg   whatever the specific operation requires
 * @param extraKg        the commander's discretion
 */
public record FuelBreakdown(double taxiKg,
                            double tripKg,
                            double contingencyKg,
                            double alternateKg,
                            double finalReserveKg,
                            double additionalKg,
                            double extraKg) {

    /** Everything that must be on board at takeoff. */
    public double takeoffFuelKg() {
        return tripKg + contingencyKg + alternateKg + finalReserveKg + additionalKg + extraKg;
    }

    /** Everything that must go into the tanks. */
    public double rampFuelKg() {
        return takeoffFuelKg() + taxiKg;
    }

    /**
     * The minimum takeoff fuel the regulation requires, ignoring the commander's extra. A plan
     * carrying less than this is not a plan.
     */
    public double minimumTakeoffFuelKg() {
        return tripKg + contingencyKg + alternateKg + finalReserveKg + additionalKg;
    }

    /** What is left in the tanks on landing at the destination as planned. */
    public double fuelOnLandingKg() {
        return takeoffFuelKg() - tripKg;
    }

    /** What would be left on landing at the alternate after a diversion. */
    public double fuelAtAlternateKg() {
        return fuelOnLandingKg() - alternateKg;
    }

    /** True when the plan leaves the final reserve untouched on arrival at the alternate. */
    public boolean protectsFinalReserve() {
        return fuelAtAlternateKg() >= finalReserveKg - 1e-6;
    }

    /** A copy with more fuel at the commander's discretion. */
    public FuelBreakdown withExtra(double newExtraKg) {
        return new FuelBreakdown(taxiKg, tripKg, contingencyKg, alternateKg, finalReserveKg,
                additionalKg, newExtraKg);
    }

    /** A copy with a different additional-fuel figure, as an ETOPS critical scenario sets. */
    public FuelBreakdown withAdditional(double newAdditionalKg) {
        return new FuelBreakdown(taxiKg, tripKg, contingencyKg, alternateKg, finalReserveKg,
                newAdditionalKg, extraKg);
    }

    /** The figures in the order an OFP prints them, for rendering. */
    public Map<String, Double> asOfpRows() {
        Map<String, Double> rows = new LinkedHashMap<>();
        rows.put("TRIP", tripKg);
        rows.put("CONT", contingencyKg);
        rows.put("ALTN", alternateKg);
        rows.put("FINRES", finalReserveKg);
        rows.put("ADDL", additionalKg);
        rows.put("EXTRA", extraKg);
        rows.put("TAKEOFF", takeoffFuelKg());
        rows.put("TAXI", taxiKg);
        rows.put("RAMP", rampFuelKg());
        return rows;
    }
}
