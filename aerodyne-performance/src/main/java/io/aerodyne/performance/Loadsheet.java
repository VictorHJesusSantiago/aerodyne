package io.aerodyne.performance;

import java.util.List;

/**
 * The result of a weight and balance calculation: what the aircraft weighs and where its centre
 * of gravity sits at each of the three weights that matter, and whether any of it is legal.
 *
 * @param type              the aircraft type the sheet was computed for
 * @param stations          the load that went into it, including the empty aircraft and the fuel
 * @param zeroFuelWeightKg  the aircraft plus payload, no fuel
 * @param takeoffWeightKg   zero-fuel weight plus the fuel on board at takeoff
 * @param landingWeightKg   takeoff weight less the trip fuel
 * @param zeroFuelCgPercentMac centre of gravity at the zero-fuel weight
 * @param takeoffCgPercentMac  centre of gravity at takeoff
 * @param landingCgPercentMac  centre of gravity at landing
 * @param exceedances       every limit the loading breaks, in plain words; empty when legal
 */
public record Loadsheet(AircraftType type,
                        List<LoadStation> stations,
                        double zeroFuelWeightKg,
                        double takeoffWeightKg,
                        double landingWeightKg,
                        double zeroFuelCgPercentMac,
                        double takeoffCgPercentMac,
                        double landingCgPercentMac,
                        List<String> exceedances) {

    public Loadsheet {
        stations = List.copyOf(stations);
        exceedances = List.copyOf(exceedances);
    }

    /** True when every weight and every centre of gravity is within limits. */
    public boolean isWithinLimits() {
        return exceedances.isEmpty();
    }

    /** The payload carried: everything that is neither the empty aircraft nor fuel. */
    public double payloadKg() {
        return zeroFuelWeightKg - type.weightLimits().operatingEmptyKg();
    }

    /** The fuel on board at takeoff. */
    public double takeoffFuelKg() {
        return takeoffWeightKg - zeroFuelWeightKg;
    }

    /** How much more the aircraft could have taken before the first limit bound. */
    public double spareCapacityKg() {
        WeightLimits limits = type.weightLimits();
        return Math.min(limits.maxZeroFuelKg() - zeroFuelWeightKg,
                Math.min(limits.maxTakeoffKg() - takeoffWeightKg,
                        limits.maxLandingKg() - landingWeightKg));
    }

    /** The permitted centre-of-gravity range at the takeoff weight, for the printed sheet. */
    public java.util.Optional<CentreOfGravityEnvelope.Limits> takeoffCgLimits() {
        return type.cgEnvelope().limitsAt(takeoffWeightKg);
    }

    /**
     * How far the takeoff centre of gravity sits from the nearer limit, as a percentage of the
     * chord. Negative when it is outside. A thin margin is what makes a last-minute load change
     * a problem, so it is worth having as a number rather than only as a verdict.
     */
    public double takeoffCgMarginPercentMac() {
        return takeoffCgLimits()
                .map(limits -> Math.min(takeoffCgPercentMac - limits.forwardPercentMac(),
                        limits.aftPercentMac() - takeoffCgPercentMac))
                .orElse(Double.NEGATIVE_INFINITY);
    }
}
