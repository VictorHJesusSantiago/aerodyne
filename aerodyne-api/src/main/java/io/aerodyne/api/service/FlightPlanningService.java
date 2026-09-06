package io.aerodyne.api.service;

import io.aerodyne.api.dto.PlanRequest;
import io.aerodyne.dispatch.AlternateCandidate;
import io.aerodyne.dispatch.AlternateCriteria;
import io.aerodyne.dispatch.AlternateSelector;
import io.aerodyne.dispatch.DiversionCalculator;
import io.aerodyne.dispatch.EtopsAerodromeScreen;
import io.aerodyne.dispatch.EtopsAnalyser;
import io.aerodyne.dispatch.EtopsAnalysis;
import io.aerodyne.dispatch.RoutePath;
import io.aerodyne.dispatch.ofp.OperationalFlightPlan;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.geo.Units;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.MinimumAltitudeGrid;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.performance.AircraftType;
import io.aerodyne.performance.FlightProfile;
import io.aerodyne.performance.FlightProfileCalculator;
import io.aerodyne.performance.FuelBreakdown;
import io.aerodyne.performance.FuelPolicy;
import io.aerodyne.performance.Loadsheet;
import io.aerodyne.performance.StepClimbPolicy;
import io.aerodyne.performance.WeightAndBalance;
import io.aerodyne.routing.AirwayGraph;
import io.aerodyne.routing.AirwayRouteSearch;
import io.aerodyne.routing.ExpandedRoute;
import io.aerodyne.routing.ProcedureAttacher;
import io.aerodyne.routing.RouteExpander;
import io.aerodyne.routing.SemicircularRule;
import io.aerodyne.routing.WindOptimalRouteSearch;
import io.aerodyne.weather.WeatherModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a planning request into a flight plan.
 *
 * <p>This is the orchestration and nothing else: every calculation it performs lives in a module
 * underneath it and is tested there without Spring. What belongs here is the order the steps run
 * in and the way each one's output becomes the next one's input — which is not trivial, because
 * some of them depend on each other in a circle.
 *
 * <p>The circle is the fuel. The takeoff weight determines the fuel burn, and the fuel burn
 * determines how much fuel is on board, which is part of the takeoff weight. It is resolved by
 * iterating: guess a takeoff weight, integrate the flight, work out the fuel that implies,
 * recompute the weight, and repeat until the weight stops moving. Two or three passes settle it
 * to within a few kilograms, and {@link #FUEL_CONVERGENCE_KG} is where it stops.
 */
@Service
public class FlightPlanningService {

    private static final Logger log = LoggerFactory.getLogger(FlightPlanningService.class);

    /** The fuel loop stops once the takeoff weight moves by less than this between passes. */
    public static final double FUEL_CONVERGENCE_KG = 20.0;
    /** How many passes it may take before the loop gives up and uses what it has. */
    public static final int MAX_FUEL_ITERATIONS = 8;
    /** How far from an aerodrome the route search will reach to join the airway network. */
    private static final double NETWORK_JOIN_RADIUS_NM = 250.0;
    /** The engine-out level a diversion is planned at, when the type does not state one. */
    private static final double DIVERSION_ALTITUDE_FT = 22_000.0;
    /** The runway an ETOPS diversion needs, which is longer than an ordinary alternate's. */
    private static final int ETOPS_MINIMUM_RUNWAY_FT = 7_000;
    /** How finely the route is walked over the terrain grid; a square is sixty miles across. */
    private static final double MORA_SAMPLE_INTERVAL_NM = 30.0;

    private final NavigationDataService navigation;
    private final AircraftTypeRegistry aircraftTypes;
    private final WeatherService weather;

    public FlightPlanningService(NavigationDataService navigation,
                                 AircraftTypeRegistry aircraftTypes,
                                 WeatherService weather) {
        this.navigation = navigation;
        this.aircraftTypes = aircraftTypes;
        this.weather = weather;
    }

    /** Plans a flight. */
    public OperationalFlightPlan plan(PlanRequest request) {
        NavigationDatabase database = navigation.database();
        AircraftType type = aircraftTypes.require(request.aircraftType());

        Airport departure = database.airport(request.departure()).orElseThrow(() ->
                new IllegalArgumentException(
                        "departure aerodrome " + request.departure() + " is not in the cycle"));
        Airport destination = database.airport(request.destination()).orElseThrow(() ->
                new IllegalArgumentException(
                        "destination aerodrome " + request.destination() + " is not in the cycle"));

        WeatherModel model = weather.forDeparture(request.offBlocks());
        double cruiseAltitude = chooseCruiseAltitude(request, type, departure, destination);
        ExpandedRoute route = resolveRoute(request, database, departure, destination,
                cruiseAltitude, type, model);
        route = withProcedures(request, database, departure, destination, route);

        if (route.elements().size() < 2) {
            throw new IllegalArgumentException(
                    "the route resolved to fewer than two points; nothing can be flown along it");
        }

        FuelResult fuelResult = converge(request, type, route, departure, destination,
                cruiseAltitude, model);

        Loadsheet loadsheet = WeightAndBalance.forType(type)
                .load("Payload", request.payloadKg(), type.operatingEmptyArmMetres())
                .fuel(fuelResult.fuel().takeoffFuelKg(), type.operatingEmptyArmMetres())
                .tripFuel(fuelResult.profile().tripFuelKg())
                .compute();

        List<AlternateCandidate> alternates =
                chooseAlternates(request, database, destination, fuelResult.profile().arrivesAt());

        EtopsAnalysis etops = assessEtops(type, route, model, fuelResult.profile(),
                request.offBlocks(), database, destination);

        return new OperationalFlightPlan(request.flightNumber(), request.registration(), type,
                departure, destination, alternates, route.toRouteString(), fuelResult.profile(),
                fuelResult.fuel(), loadsheet, etops,
                database.cycle().orElse(null), model.source(), request.offBlocks(),
                Instant.now(), highestMoraAlong(route, database));
    }

    /**
     * Joins the departure and arrival procedures onto the route, when the caller asked for them.
     *
     * <p>Only when asked. A first look at a sector wants the enroute distance and nothing else,
     * and attaching a procedure needs a runway, which is not known until much closer to the day.
     * A caller that names a runway or a procedure is asking; one that names neither is not.
     */
    private ExpandedRoute withProcedures(PlanRequest request, NavigationDatabase database,
                                         Airport departure, Airport destination,
                                         ExpandedRoute route) {
        if (!request.wantsProcedures()) {
            return route;
        }
        ProcedureAttacher.Result attached = new ProcedureAttacher(database).attach(
                route, departure, destination,
                request.departureRunway(), request.sid(),
                request.arrivalRunway(), request.star());

        attached.departure().ifPresent(sid ->
                log.debug("attached departure {} at {}", sid, departure.icaoCode()));
        attached.arrival().ifPresent(star ->
                log.debug("attached arrival {} at {}", star, destination.icaoCode()));
        return attached.route();
    }

    // ------------------------------------------------------------------ the fuel loop

    /**
     * Resolves the circular dependency between takeoff weight and fuel burn.
     *
     * <p>The first pass starts from the zero-fuel weight plus a rough fuel guess. Each pass
     * integrates the flight at the current weight, applies the fuel policy to the burn it found,
     * and recomputes the weight from the fuel that implies. The weight falls monotonically
     * towards its fixed point from an overestimate, so the loop converges from above — which is
     * the safe direction to approach from if it has to be cut short.
     */
    private FuelResult converge(PlanRequest request, AircraftType type, ExpandedRoute route,
                                Airport departure, Airport destination, double cruiseAltitude,
                                WeatherModel model) {
        FlightProfileCalculator calculator = new FlightProfileCalculator(type, model);
        FuelPolicy policy = fuelPolicyFor(type);

        double zeroFuelWeight = type.weightLimits().operatingEmptyKg() + request.payloadKg();
        // A first guess: enough for the whole route at the cruise fuel flow, which overstates it.
        double takeoffWeight = Math.min(type.weightLimits().maxTakeoffKg(),
                zeroFuelWeight + initialFuelGuess(type, route, cruiseAltitude));

        FlightProfile profile = null;
        FuelBreakdown fuel = null;
        for (int pass = 0; pass < MAX_FUEL_ITERATIONS; pass++) {
            profile = calculator.compute(new FlightProfileCalculator.Request(
                    route.path(), takeoffWeight, cruiseAltitude,
                    departure.elevationFt(), destination.elevationFt(), request.offBlocks(),
                    stepClimbPolicy(request)));

            fuel = policy.apply(profile.tripFuelKg(), alternateFuelFor(type, profile),
                    0.0, request.extraFuelKg());
            double revised = zeroFuelWeight + fuel.takeoffFuelKg();

            if (Math.abs(revised - takeoffWeight) < FUEL_CONVERGENCE_KG) {
                takeoffWeight = revised;
                break;
            }
            takeoffWeight = revised;
            if (pass == MAX_FUEL_ITERATIONS - 1) {
                log.warn("the fuel loop did not settle for {}-{} after {} passes; using {} kg",
                        request.departure(), request.destination(), MAX_FUEL_ITERATIONS,
                        Math.round(takeoffWeight));
            }
        }

        // One last integration at the settled weight, so the profile matches the fuel figures.
        profile = calculator.compute(new FlightProfileCalculator.Request(
                route.path(), takeoffWeight, cruiseAltitude,
                departure.elevationFt(), destination.elevationFt(), request.offBlocks(),
                stepClimbPolicy(request)));
        return new FuelResult(profile, fuel);
    }

    /**
     * Whether to plan step climbs. Off unless asked for, because a stepped plan has to be flown
     * as one: the crew has to get each level from ATC, and a plan that assumed levels nobody
     * agreed to is a plan that arrives short of its fuel.
     */
    private static StepClimbPolicy stepClimbPolicy(PlanRequest request) {
        return request.wantsStepClimb() ? StepClimbPolicy.standard() : StepClimbPolicy.NONE;
    }

    /**
     * The fuel to reach the alternate: a missed approach, the transit and an approach. Estimated
     * from the cruise fuel flow at a lower level rather than integrated, because the alternate
     * is a contingency and integrating a route nobody has chosen yet would be false precision.
     */
    private static double alternateFuelFor(AircraftType type, FlightProfile profile) {
        double hours = 0.5;
        return type.cruise().fuelFlowKgPerHour(profile.landingWeightKg(), 20_000.0,
                io.aerodyne.weather.AtmosphericConditions.isa(20_000.0)) * hours;
    }

    /** A fuel policy from the type's own figures: holding at its cruise flow, and taxi by size. */
    private static FuelPolicy fuelPolicyFor(AircraftType type) {
        double holdingFlow = type.cruise().fuelFlowKgPerHour(
                type.weightLimits().maxLandingKg(), 15_000.0,
                io.aerodyne.weather.AtmosphericConditions.isa(15_000.0));
        double taxi = type.weightLimits().maxTakeoffKg() > 150_000 ? 600.0 : 200.0;
        return FuelPolicy.standard(holdingFlow, taxi);
    }

    private static double initialFuelGuess(AircraftType type, ExpandedRoute route,
                                           double cruiseAltitude) {
        var isa = io.aerodyne.weather.AtmosphericConditions.isa(cruiseAltitude);
        double weight = type.weightLimits().maxTakeoffKg();
        double speed = type.cruise().trueAirspeedKt(weight, cruiseAltitude, isa);
        double hours = speed <= 0 ? 0 : route.distanceNm() / speed;
        // A half again over the still-air cruise burn covers the climb, the reserves and a wind.
        return type.cruise().fuelFlowKgPerHour(weight, cruiseAltitude, isa) * hours * 1.5;
    }

    // ------------------------------------------------------------------ route and level

    private ExpandedRoute resolveRoute(PlanRequest request, NavigationDatabase database,
                                       Airport departure, Airport destination,
                                       double cruiseAltitude, AircraftType type,
                                       WeatherModel model) {
        if (!request.needsRouteSearch()) {
            return new RouteExpander(database).expand(request.route(), departure.position());
        }

        AirwayGraph graph = AirwayGraph.build(database, cruiseAltitude);

        // Optimise time against the forecast when there is one to optimise against, and distance
        // when there is not. Running the wind search on a uniform fallback wind would cost the
        // extra node expansions and return the shortest route anyway.
        if (request.optimiseForWind() && model.covers(departure.position(), request.offBlocks())) {
            double airspeed = type.cruise().trueAirspeedKt(
                    type.weightLimits().maxTakeoffKg(), cruiseAltitude,
                    io.aerodyne.weather.AtmosphericConditions.isa(cruiseAltitude));
            WindOptimalRouteSearch.Result quickest =
                    new WindOptimalRouteSearch(graph, model, cruiseAltitude, airspeed,
                            request.offBlocks())
                            .searchNear(departure, destination, NETWORK_JOIN_RADIUS_NM);
            if (quickest.found()) {
                log.debug("wind-optimal route {}-{}: {} nm in {} min, {} nodes",
                        departure.icaoCode(), destination.icaoCode(),
                        Math.round(quickest.distanceNm()), Math.round(quickest.timeMinutes()),
                        quickest.nodesExpanded());
                return new ExpandedRoute(quickest.toRouteString(), quickest.elements(), List.of());
            }
        }

        AirwayRouteSearch.Result found = new AirwayRouteSearch(graph)
                .searchNear(departure, destination, NETWORK_JOIN_RADIUS_NM);

        if (found.found()) {
            return new ExpandedRoute(found.toRouteString(), found.elements(), List.of());
        }
        // Nothing over the network: a direct track is a legal plan and an honest answer, and the
        // caller can see from the route string that no airways were used.
        log.info("no airway route from {} to {} at FL{}; planning direct",
                departure.icaoCode(), destination.icaoCode(),
                Units.pressureAltitudeToFlightLevel(cruiseAltitude));
        return new ExpandedRoute(departure.icaoCode() + " DCT " + destination.icaoCode(),
                List.of(io.aerodyne.routing.RouteElement.direct(departure, departure.icaoCode()),
                        io.aerodyne.routing.RouteElement.direct(destination, destination.icaoCode())),
                List.of());
    }

    /**
     * The cruise level: the one requested, or the aircraft's optimum altitude moved to the
     * nearest level the semicircular rule allows on this track.
     */
    private static double chooseCruiseAltitude(PlanRequest request, AircraftType type,
                                               Airport departure, Airport destination) {
        if (!request.needsLevelSelection()) {
            return Units.flightLevelToFeet(request.cruiseFlightLevel());
        }
        double track = Geodesy.initialBearingDeg(departure.position(), destination.position());
        double optimum = type.cruise().optimumAltitudeFt(
                (type.weightLimits().maxTakeoffKg() + type.weightLimits().maxLandingKg()) / 2.0);
        int desired = Units.pressureAltitudeToFlightLevel(optimum);
        int ceiling = Units.pressureAltitudeToFlightLevel(type.climb().ceilingFt());

        return SemicircularRule.nearestAvailableLevel(track, desired, 100, ceiling)
                .stream()
                .mapToDouble(Units::flightLevelToFeet)
                .findFirst()
                .orElse(optimum);
    }

    // ------------------------------------------------------------------ alternates and ETOPS

    private List<AlternateCandidate> chooseAlternates(PlanRequest request,
                                                      NavigationDatabase database,
                                                      Airport destination, Instant arrival) {
        AlternateSelector selector = new AlternateSelector(database);
        AlternateCriteria criteria = AlternateCriteria.forJetWithPrecisionApproach();

        if (!request.alternates().isEmpty()) {
            // A named alternate is still screened; the caller chose it, the engine still checks it.
            return request.alternates().stream()
                    .map(database::airport)
                    .flatMap(Optional::stream)
                    .map(airport -> selector.screen(airport.position(), criteria.withinNm(1.0),
                                    weather.forecasts(), weather.notams(), arrival).stream()
                            .filter(candidate -> candidate.icaoCode().equals(airport.icaoCode()))
                            .findFirst()
                            .orElse(new AlternateCandidate(airport,
                                    Geodesy.distanceNm(destination.position(), airport.position()),
                                    Geodesy.initialBearingDeg(destination.position(),
                                            airport.position()),
                                    null, List.of())))
                    .toList();
        }
        return selector.select(destination.position(), criteria,
                weather.forecasts(), weather.notams(), arrival);
    }

    /**
     * Assesses the route against the type's ETOPS approval, when it has one. A type with no
     * approval is not assessed: the ordinary sixty-minute rule applies and a route that breaks
     * it is not a route this operator can fly at all, which the route search would have to
     * enforce rather than the assessment reporting after the fact.
     */
    private EtopsAnalysis assessEtops(AircraftType type, ExpandedRoute route, WeatherModel model,
                                      FlightProfile profile, Instant offBlocks,
                                      NavigationDatabase database, Airport destination) {
        if (!type.isEtopsApproved()) {
            return null;
        }
        RoutePath path = new RoutePath(route.path());
        int approval = type.etopsApproval().orElse(60);

        // En-route alternate minima, not destination ones: the search radius is the route's
        // reach rather than the destination's, and the runway requirement is the aircraft's.
        AlternateCriteria enRoute = AlternateCriteria.forJetWithPrecisionApproach()
                .withMinimumRunwayFt(ETOPS_MINIMUM_RUNWAY_FT);
        EtopsAerodromeScreen.Result screened = new EtopsAerodromeScreen(database).along(
                path, enRoute, weather.forecasts(), weather.notams(), offBlocks,
                profile.averageGroundSpeedKt(), approval);

        if (screened.isEmpty()) {
            log.warn("no adequate aerodrome anywhere near the route; skipping the ETOPS assessment");
            return null;
        }
        if (!screened.weatherScreened()) {
            log.info("no forecast covered any en-route alternate for {}-{}; "
                            + "screening them on runway length only",
                    route.toRouteString().isEmpty() ? "?" : departure(route),
                    destination.icaoCode());
        }

        DiversionCalculator diversions = new DiversionCalculator(model,
                type.diversionSpeedKt(), DIVERSION_ALTITUDE_FT);
        return new EtopsAnalyser(diversions).analyse(path, screened.adequate(), approval,
                offBlocks, profile.averageGroundSpeedKt(), screened.weatherScreened());
    }

    /** The first token of a route string, for a log line. */
    private static String departure(ExpandedRoute route) {
        String text = route.toRouteString();
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    /**
     * The highest grid minimum off-route altitude the route crosses, or null when the cycle
     * published no grid at all.
     *
     * <p>Null rather than zero, and the two mean opposite things: zero would say the route is
     * over water at sea level, and null says nobody told us. Only a figure that was actually
     * surveyed reaches the dispatch decision.
     */
    private static Integer highestMoraAlong(ExpandedRoute route, NavigationDatabase database) {
        if (database.minimumAltitudes().isEmpty()) {
            return null;
        }
        MinimumAltitudeGrid.Along along =
                database.minimumAltitudes().along(route.path(), MORA_SAMPLE_INTERVAL_NM);
        if (along.hasGaps()) {
            log.debug("the grid covers {} of {} samples along the route",
                    along.samples() - along.unsurveyed(), along.samples());
        }
        return along.highest().isPresent() ? along.highest().getAsInt() : null;
    }

    /** The integrated profile and the fuel that goes with it. */
    private record FuelResult(FlightProfile profile, FuelBreakdown fuel) {
    }
}
