package io.aerodyne.performance;

/**
 * The certified mass limits of an aircraft type, in kilograms.
 *
 * <p>They interact: the takeoff weight a flight can plan is the lowest of the structural
 * takeoff limit, the zero-fuel limit plus the fuel actually carried, and the landing limit plus
 * the fuel expected to burn off. {@link #maximumTakeoffWeightKg} does that arithmetic, and it is
 * the calculation that decides whether a payload flies today.
 *
 * @param operatingEmptyKg  the aircraft with its crew and equipment, no payload and no fuel
 * @param maxZeroFuelKg     the heaviest the aircraft may be before fuel is loaded
 * @param maxTakeoffKg      the structural takeoff limit
 * @param maxLandingKg      the structural landing limit
 * @param maxFuelKg         usable fuel capacity
 */
public record WeightLimits(double operatingEmptyKg,
                           double maxZeroFuelKg,
                           double maxTakeoffKg,
                           double maxLandingKg,
                           double maxFuelKg) {

    public WeightLimits {
        if (operatingEmptyKg <= 0 || maxZeroFuelKg <= operatingEmptyKg) {
            throw new IllegalArgumentException(
                    "zero-fuel weight must exceed the operating empty weight");
        }
        if (maxTakeoffKg < maxZeroFuelKg) {
            throw new IllegalArgumentException(
                    "takeoff weight limit is below the zero-fuel limit");
        }
        if (maxLandingKg > maxTakeoffKg || maxLandingKg < maxZeroFuelKg) {
            throw new IllegalArgumentException(
                    "landing weight limit must lie between the zero-fuel and takeoff limits");
        }
        if (maxFuelKg <= 0) {
            throw new IllegalArgumentException("fuel capacity must be positive");
        }
    }

    /** The heaviest payload the zero-fuel limit allows. */
    public double maxPayloadKg() {
        return maxZeroFuelKg - operatingEmptyKg;
    }

    /**
     * The heaviest the aircraft may leave the ground at, given the fuel on board and the fuel
     * that will have burned by landing.
     *
     * <p>Three limits bind at once and the lowest wins: the structure, the zero-fuel weight
     * plus what is in the tanks, and the landing weight plus what will be gone by then.
     */
    public double maximumTakeoffWeightKg(double fuelOnBoardKg, double tripFuelKg) {
        return Math.min(maxTakeoffKg,
                Math.min(maxZeroFuelKg + fuelOnBoardKg, maxLandingKg + tripFuelKg));
    }

    /** The payload that fits once the fuel is loaded and the takeoff limits are respected. */
    public double availablePayloadKg(double fuelOnBoardKg, double tripFuelKg) {
        double takeoffLimit = maximumTakeoffWeightKg(fuelOnBoardKg, tripFuelKg);
        return Math.max(0.0, Math.min(maxPayloadKg(), takeoffLimit - operatingEmptyKg - fuelOnBoardKg));
    }

    /** True when a takeoff weight is within every structural limit. */
    public boolean permitsTakeoffAt(double takeoffWeightKg, double fuelOnBoardKg, double tripFuelKg) {
        return takeoffWeightKg <= maximumTakeoffWeightKg(fuelOnBoardKg, tripFuelKg);
    }
}
