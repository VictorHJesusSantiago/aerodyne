package io.aerodyne.api.dto;

import io.aerodyne.dispatch.ofp.OperationalFlightPlan;
import io.aerodyne.geo.Units;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A computed plan, as the API returns it.
 *
 * <p>Deliberately a summary rather than the whole plan. The navigation log runs to a hundred
 * legs and belongs in the OFP document, which is fetched separately; what a caller wants back
 * from the planning call is whether it can go, how much fuel to order, and when it lands.
 *
 * @param reference       the plan's identifier, used to fetch the OFP
 * @param flightNumber    as requested
 * @param sector          {@code EGLL-LEBL}
 * @param aircraftType    the type planned for
 * @param route           the route as filed, whether given or searched for
 * @param cruiseFlightLevel the level planned
 * @param offBlocks       when the flight starts
 * @param estimatedArrival when it lands
 * @param distanceNm      the route's length
 * @param tripTimeMinutes takeoff to landing
 * @param fuel            the fuel breakdown
 * @param takeoffWeightKg the planned takeoff weight
 * @param landingWeightKg the planned landing weight
 * @param alternates      the alternates held
 * @param etops           the ETOPS assessment in one line, or {@code null} when none applied
 * @param dispatchable    whether the plan may be dispatched
 * @param objections      why not, when it may not
 * @param airacCycle      the navigation data used
 * @param weatherSource   where the winds came from
 */
public record PlanResponse(UUID reference,
                           String flightNumber,
                           String sector,
                           String aircraftType,
                           String route,
                           int cruiseFlightLevel,
                           Instant offBlocks,
                           Instant estimatedArrival,
                           double distanceNm,
                           double tripTimeMinutes,
                           FuelSummary fuel,
                           double takeoffWeightKg,
                           double landingWeightKg,
                           List<AlternateSummary> alternates,
                           String etops,
                           boolean dispatchable,
                           List<String> objections,
                           String airacCycle,
                           String weatherSource) {

    /** The fuel figures, in kilograms. */
    public record FuelSummary(double taxiKg, double tripKg, double contingencyKg,
                              double alternateKg, double finalReserveKg, double additionalKg,
                              double extraKg, double takeoffKg, double rampKg) {
    }

    /** One alternate, as the response reports it. */
    public record AlternateSummary(String icaoCode, double distanceNm, boolean usable,
                                   List<String> rejections) {
    }

    /** Builds the response from a computed plan. */
    public static PlanResponse from(UUID reference, OperationalFlightPlan plan) {
        var fuel = plan.fuel();
        return new PlanResponse(
                reference,
                plan.flightNumber(),
                plan.sectorLabel(),
                plan.aircraftType().typeCode(),
                plan.routeString(),
                Units.pressureAltitudeToFlightLevel(plan.profile().cruiseAltitudeFt()),
                plan.offBlocks(),
                plan.estimatedArrival(),
                round(plan.distanceNm(), 1),
                round(plan.profile().tripTimeMinutes(), 1),
                new FuelSummary(
                        round(fuel.taxiKg(), 0), round(fuel.tripKg(), 0),
                        round(fuel.contingencyKg(), 0), round(fuel.alternateKg(), 0),
                        round(fuel.finalReserveKg(), 0), round(fuel.additionalKg(), 0),
                        round(fuel.extraKg(), 0), round(fuel.takeoffFuelKg(), 0),
                        round(fuel.rampFuelKg(), 0)),
                round(plan.loadsheet().takeoffWeightKg(), 0),
                round(plan.loadsheet().landingWeightKg(), 0),
                plan.alternates().stream()
                        .map(alternate -> new AlternateSummary(alternate.icaoCode(),
                                round(alternate.distanceNm(), 1), alternate.isUsable(),
                                alternate.rejections()))
                        .toList(),
                plan.etopsAssessment().map(analysis -> analysis.summary()).orElse(null),
                plan.isDispatchable(),
                plan.dispatchObjections(),
                plan.airacCycle() == null ? null : plan.airacCycle().identifier(),
                plan.weatherSource());
    }

    /**
     * Rounds a figure for publication. Fuel is quoted to the kilogram and distances to a tenth
     * of a mile; carrying the full double would suggest a precision the forecast does not have.
     */
    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
