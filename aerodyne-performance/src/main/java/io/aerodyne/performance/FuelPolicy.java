package io.aerodyne.performance;

/**
 * The operator's fuel policy: how much fuel the regulation and the operations manual require on
 * top of what the flight will actually burn.
 *
 * <p>The structure follows the commercial air transport fuel scheme. Each component answers a
 * different question, and conflating them is how a plan ends up legal-looking and short:
 *
 * <ul>
 *   <li><b>Taxi</b> — burned before takeoff, so it is on the ramp but not in the air.</li>
 *   <li><b>Trip</b> — takeoff to landing at the destination, from the integrated profile.</li>
 *   <li><b>Contingency</b> — for the things the plan could not know: a level not available, a
 *       wind that did not verify, a routing longer than filed. A percentage of the trip fuel,
 *       with a floor of a few minutes' holding so that a short sector is not left with almost
 *       nothing.</li>
 *   <li><b>Alternate</b> — a missed approach at the destination, the transit, and an approach
 *       and landing at the alternate.</li>
 *   <li><b>Final reserve</b> — holding fuel that may not be planned into, ever. It is what
 *       makes a diversion decision a decision rather than an emergency.</li>
 *   <li><b>Additional</b> — whatever a specific operation requires beyond the above: the
 *       critical fuel scenario on an ETOPS sector, an isolated aerodrome.</li>
 *   <li><b>Extra</b> — the commander's discretion, which nothing computes.</li>
 * </ul>
 *
 * @param contingencyFraction    the fraction of trip fuel carried as contingency
 * @param contingencyFloorMinutes the minimum contingency, expressed as minutes of holding
 * @param finalReserveMinutes    the final reserve, as minutes of holding
 * @param holdingFuelFlowKgPerHour the fuel flow the holding-based components are computed at
 * @param taxiFuelKg             fuel burned between off-blocks and takeoff
 */
public record FuelPolicy(double contingencyFraction,
                         int contingencyFloorMinutes,
                         int finalReserveMinutes,
                         double holdingFuelFlowKgPerHour,
                         double taxiFuelKg) {

    public FuelPolicy {
        if (contingencyFraction < 0 || contingencyFraction > 1) {
            throw new IllegalArgumentException(
                    "contingency must be a fraction between 0 and 1, got " + contingencyFraction);
        }
        if (finalReserveMinutes < 0 || holdingFuelFlowKgPerHour < 0 || taxiFuelKg < 0) {
            throw new IllegalArgumentException("fuel policy figures cannot be negative");
        }
    }

    /**
     * The usual scheme for a turbine aeroplane: five percent contingency with a five-minute
     * floor, and thirty minutes of final reserve.
     */
    public static FuelPolicy standard(double holdingFuelFlowKgPerHour, double taxiFuelKg) {
        return new FuelPolicy(0.05, 5, 30, holdingFuelFlowKgPerHour, taxiFuelKg);
    }

    /** The fuel a number of minutes of holding costs. */
    public double holdingFuelKg(double minutes) {
        return holdingFuelFlowKgPerHour * minutes / 60.0;
    }

    /**
     * Computes the breakdown.
     *
     * @param tripFuelKg      takeoff to landing at the destination
     * @param alternateFuelKg destination to the alternate, including the missed approach
     * @param additionalFuelKg anything a specific operation requires
     * @param extraFuelKg     the commander's discretion
     */
    public FuelBreakdown apply(double tripFuelKg, double alternateFuelKg,
                               double additionalFuelKg, double extraFuelKg) {
        double contingency = Math.max(tripFuelKg * contingencyFraction,
                holdingFuelKg(contingencyFloorMinutes));
        return new FuelBreakdown(taxiFuelKg, tripFuelKg, contingency, alternateFuelKg,
                holdingFuelKg(finalReserveMinutes), additionalFuelKg, extraFuelKg);
    }

    /** The breakdown with no alternate — a destination that qualifies for an island reserve. */
    public FuelBreakdown applyWithoutAlternate(double tripFuelKg, double additionalFuelKg,
                                               double extraFuelKg) {
        return apply(tripFuelKg, 0.0, additionalFuelKg, extraFuelKg);
    }
}
