package io.aerodyne.dispatch.ofp;

import io.aerodyne.dispatch.AlternateCandidate;
import io.aerodyne.dispatch.EtopsAnalysis;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.performance.AircraftType;
import io.aerodyne.performance.FlightProfile;
import io.aerodyne.performance.FuelBreakdown;
import io.aerodyne.performance.Loadsheet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The operational flight plan: everything computed for one flight, gathered into the document
 * the crew is dispatched with.
 *
 * <p>This is a record of a decision, not a calculation. It carries the inputs the decision was
 * made on — the AIRAC cycle, the wind source, the aircraft — alongside the outputs, because a
 * plan that cannot be reproduced cannot be defended afterwards. {@link #isDispatchable()} is the
 * verdict; {@link #dispatchObjections()} is why, when it is negative.
 *
 * @param flightNumber   the commercial flight number
 * @param registration   the aircraft's registration
 * @param aircraftType   what it is
 * @param departure      where it leaves from
 * @param destination    where it goes
 * @param alternates     the alternates held, in order of preference
 * @param routeString    the route as it will be filed
 * @param profile        the integrated flight
 * @param fuel           the fuel breakdown
 * @param loadsheet      the weight and balance
 * @param etops          the ETOPS assessment, when the route needed one
 * @param airacCycle     the navigation data the plan was built on
 * @param weatherSource  where the winds came from
 * @param offBlocks      the planned off-blocks time
 * @param preparedAt     when the plan was computed
 */
public record OperationalFlightPlan(String flightNumber,
                                    String registration,
                                    AircraftType aircraftType,
                                    Airport departure,
                                    Airport destination,
                                    List<AlternateCandidate> alternates,
                                    String routeString,
                                    FlightProfile profile,
                                    FuelBreakdown fuel,
                                    Loadsheet loadsheet,
                                    EtopsAnalysis etops,
                                    AiracCycle airacCycle,
                                    String weatherSource,
                                    Instant offBlocks,
                                    Instant preparedAt,
                                    Integer highestMoraFt) {

    /** A plan made against a cycle with no grid minimum off-route altitudes in it. */
    public OperationalFlightPlan(String flightNumber, String registration,
                                 AircraftType aircraftType, Airport departure, Airport destination,
                                 List<AlternateCandidate> alternates, String routeString,
                                 FlightProfile profile, FuelBreakdown fuel, Loadsheet loadsheet,
                                 EtopsAnalysis etops, AiracCycle airacCycle, String weatherSource,
                                 Instant offBlocks, Instant preparedAt) {
        this(flightNumber, registration, aircraftType, departure, destination, alternates,
                routeString, profile, fuel, loadsheet, etops, airacCycle, weatherSource,
                offBlocks, preparedAt, null);
    }

    public OperationalFlightPlan {
        alternates = List.copyOf(alternates);
    }

    /**
     * The highest grid minimum off-route altitude anywhere along the route, when the cycle
     * published one.
     *
     * <p>Empty is not the same as zero and must not be read as it. Most regional navdata
     * extracts carry no grid MORA at all, and a route over open ocean legitimately crosses
     * squares nobody surveyed.
     */
    public java.util.OptionalInt highestMora() {
        return highestMoraFt == null
                ? java.util.OptionalInt.empty()
                : java.util.OptionalInt.of(highestMoraFt);
    }

    /** The estimated time of arrival at the destination. */
    public Instant estimatedArrival() {
        return profile.arrivesAt();
    }

    /** The route's length as planned. */
    public double distanceNm() {
        return profile.distanceNm();
    }

    public Optional<EtopsAnalysis> etopsAssessment() {
        return Optional.ofNullable(etops);
    }

    /** The alternate the plan is fuelled for. */
    public Optional<AlternateCandidate> primaryAlternate() {
        return alternates.stream().findFirst();
    }

    /**
     * Everything that stops the flight being dispatched as planned. An empty list is the
     * dispatch release.
     */
    public List<String> dispatchObjections() {
        List<String> objections = new java.util.ArrayList<>();

        if (!aircraftType.isDispatchable()) {
            objections.add(aircraftType.provenance().objection());
        }
        if (!loadsheet.isWithinLimits()) {
            objections.addAll(loadsheet.exceedances());
        }
        if (fuel.rampFuelKg() > aircraftType.weightLimits().maxFuelKg()) {
            objections.add(String.format(
                    "ramp fuel %.0f kg exceeds the tank capacity of %.0f kg",
                    fuel.rampFuelKg(), aircraftType.weightLimits().maxFuelKg()));
        }
        if (!fuel.protectsFinalReserve()) {
            objections.add("the plan does not leave the final reserve intact at the alternate");
        }
        if (alternates.isEmpty()) {
            objections.add("no usable alternate");
        }
        if (etops != null && !etops.weatherScreened() && !etops.isWithinThreshold()) {
            objections.add("the en-route alternates were screened on runway length only; "
                    + "no forecast covered them at the time a diversion would arrive");
        }
        if (etops != null && !etops.isCompliant()) {
            objections.add(String.format(
                    "the route needs an ETOPS approval of %d minutes; %d is held",
                    etops.requiredApprovalMinutes(), etops.approvedMinutes()));
        }
        if (!profile.usedForecastWind()) {
            objections.add("the wind forecast does not cover the whole route");
        }
        if (highestMoraFt != null && profile.cruiseAltitudeFt() < highestMoraFt) {
            objections.add(String.format(
                    "the cruise level of %.0f ft is below the minimum off-route altitude of "
                            + "%d ft on this route",
                    profile.cruiseAltitudeFt(), highestMoraFt));
        }
        return List.copyOf(objections);
    }

    /** True when nothing stands in the way of dispatch. */
    public boolean isDispatchable() {
        return dispatchObjections().isEmpty();
    }

    /** {@code EGLL-LEBL} — how the plan is referred to. */
    public String sectorLabel() {
        return departure.icaoCode() + "-" + destination.icaoCode();
    }
}
