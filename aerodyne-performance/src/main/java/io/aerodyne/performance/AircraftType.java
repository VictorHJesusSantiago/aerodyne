package io.aerodyne.performance;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Everything about an aircraft type that planning a flight depends on: what it may weigh, how
 * it climbs, cruises and descends, where its centre of gravity may sit, and what it can do on
 * one engine.
 *
 * <p>Geometry is expressed against the aircraft's reference datum, an arbitrary point the
 * manufacturer picks; arms are distances aft of it. The mean aerodynamic chord is located the
 * same way, and centres of gravity are converted to a percentage of it — which is the form the
 * envelope is published in and the form a loadsheet prints.
 *
 * @param typeCode              the ICAO type designator filed in item 9
 * @param name                  the type's full name
 * @param wakeCategory          the wake turbulence category
 * @param engineCount           how many engines it has
 * @param weightLimits          the certified mass limits
 * @param cruise                cruise Mach, fuel flow and optimum altitude
 * @param climb                 cumulative climb tables
 * @param descent               cumulative descent tables
 * @param cgEnvelope            the certified centre-of-gravity envelope
 * @param macLengthMetres       the length of the mean aerodynamic chord
 * @param macLeadingEdgeArmMetres where the chord's leading edge sits relative to the datum
 * @param operatingEmptyArmMetres the centre of gravity of the aircraft at its operating empty weight
 * @param diversionSpeedKt      the true airspeed an engine-out diversion is planned at
 * @param diversionFuelFlowKgPerHour the fuel flow to plan a diversion on
 * @param singleEngineCeilingFt the level the aircraft holds with one engine shut down
 * @param etopsApprovalMinutes  the diversion time the operator is approved for, or {@code null}
 * @param provenance            where the figures came from; a plan on representative data is
 *                              refused, so this reaches the dispatch decision rather than
 *                              sitting in a comment
 */
public record AircraftType(String typeCode,
                           String name,
                           WakeCategory wakeCategory,
                           int engineCount,
                           WeightLimits weightLimits,
                           CruisePerformance cruise,
                           VerticalPerformance climb,
                           VerticalPerformance descent,
                           CentreOfGravityEnvelope cgEnvelope,
                           double macLengthMetres,
                           double macLeadingEdgeArmMetres,
                           double operatingEmptyArmMetres,
                           double diversionSpeedKt,
                           double diversionFuelFlowKgPerHour,
                           double singleEngineCeilingFt,
                           Integer etopsApprovalMinutes,
                           DataProvenance provenance) {

    public AircraftType {
        provenance = provenance == null ? DataProvenance.REPRESENTATIVE : provenance;
        Objects.requireNonNull(typeCode, "typeCode");
        Objects.requireNonNull(weightLimits, "weightLimits");
        Objects.requireNonNull(cruise, "cruise");
        if (engineCount < 1) {
            throw new IllegalArgumentException(typeCode + " must have at least one engine");
        }
        if (macLengthMetres <= 0) {
            throw new IllegalArgumentException(typeCode + " must have a positive chord length");
        }
        if (etopsApprovalMinutes != null && engineCount != 2) {
            throw new IllegalArgumentException(
                    "ETOPS applies to twin-engine aeroplanes; " + typeCode
                            + " has " + engineCount + " engines");
        }
    }

    /** Converts a centre of gravity given as an arm into a percentage of the chord. */
    public double armToPercentMac(double armMetres) {
        return (armMetres - macLeadingEdgeArmMetres) / macLengthMetres * 100.0;
    }

    /** Converts a centre of gravity given as a percentage of the chord back into an arm. */
    public double percentMacToArm(double percentMac) {
        return macLeadingEdgeArmMetres + percentMac / 100.0 * macLengthMetres;
    }

    /** True when a flight may actually be dispatched on these figures. */
    public boolean isDispatchable() {
        return provenance.isDispatchable();
    }

    /** The operator's approved diversion time, when the type is approved at all. */
    public OptionalInt etopsApproval() {
        return etopsApprovalMinutes == null
                ? OptionalInt.empty()
                : OptionalInt.of(etopsApprovalMinutes);
    }

    /** True when a route may be planned beyond the ordinary sixty-minute diversion limit. */
    public boolean isEtopsApproved() {
        return etopsApprovalMinutes != null && etopsApprovalMinutes > 60;
    }

    /**
     * The distance an engine-out diversion covers in the approved time, in still air. This is
     * the radius the ETOPS check draws around each adequate aerodrome.
     */
    public double etopsRangeNm() {
        return etopsApproval().orElse(60) / 60.0 * diversionSpeedKt;
    }
}
