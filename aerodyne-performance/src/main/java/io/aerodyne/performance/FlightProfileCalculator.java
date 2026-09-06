package io.aerodyne.performance;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.weather.AtmosphericConditions;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.WindTriangle;
import io.aerodyne.weather.WindVector;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Integrates a route into a flight profile: leg by leg, against the forecast wind, tracking the
 * weight down as fuel burns.
 *
 * <p>Three things make this more than distance divided by speed.
 *
 * <p><b>The weight changes as you go.</b> Fuel flow depends on weight and the aircraft gets
 * lighter, so a leg's burn depends on the burn of every leg before it. Each cruise leg is
 * integrated with a predictor-corrector step — burn it once at the entry weight, then again at
 * the mean of the entry and predicted exit weights — which removes most of the bias that using
 * the entry weight alone would leave.
 *
 * <p><b>The wind changes as you go.</b> It is sampled at each leg's midpoint, at the altitude
 * being flown and at the time the aircraft is there, which means the time has to be integrated
 * alongside the fuel rather than worked out afterwards. Long legs are subdivided so that no
 * step spans more than {@link #MAX_STEP_NM}: a single 500-nm step across a jet stream would
 * sample one wind for the whole crossing.
 *
 * <p><b>Climb and descent are not cruise.</b> The tables give each phase's time, fuel and
 * still-air distance as a whole. The still-air distance is converted to a ground distance
 * against the wind at the phase's mid-altitude — a climb into a headwind eats less of the route
 * than one downwind — and the route is split at the resulting top-of-climb and top-of-descent.
 * Legs inside those bands take their share of the phase's time and fuel.
 *
 * <p><b>The best level rises as the fuel burns.</b> An aircraft at its top of climb is at its
 * heaviest and its optimum altitude is at its lowest; by the top of descent both have moved. A
 * cruise held at one level is therefore below its optimum for most of a long sector. When the
 * request carries a {@link StepClimbPolicy}, each cruise leg is tested against the level above:
 * the step is taken when the climb's fuel plus the remaining cruise at the higher level comes to
 * less than the remaining cruise at the present one. That test is on fuel rather than on
 * efficiency, so a step taken too late to earn back what it cost is declined.
 */
public final class FlightProfileCalculator {

    /** No integration step covers more than this, so the wind is resampled often enough. */
    public static final double MAX_STEP_NM = 100.0;

    private final AircraftType type;
    private final WeatherModel weather;

    public FlightProfileCalculator(AircraftType type, WeatherModel weather) {
        this.type = type;
        this.weather = weather;
    }

    /**
     * What to integrate.
     *
     * @param path                   the route, at least two points, departure first
     * @param takeoffWeightKg        the weight at the start
     * @param cruiseAltitudeFt       the level to cruise at; capped at the aircraft's ceiling
     * @param departureElevationFt   field elevation at the departure aerodrome
     * @param destinationElevationFt field elevation at the destination
     * @param offBlocks              the time the integration starts from
     * @param stepClimb              when the cruise may step up to a higher level
     */
    public record Request(List<GeoPoint> path,
                          double takeoffWeightKg,
                          double cruiseAltitudeFt,
                          double departureElevationFt,
                          double destinationElevationFt,
                          Instant offBlocks,
                          StepClimbPolicy stepClimb) {

        /** A request that holds one level throughout, which is the conventional profile. */
        public Request(List<GeoPoint> path, double takeoffWeightKg, double cruiseAltitudeFt,
                       double departureElevationFt, double destinationElevationFt,
                       Instant offBlocks) {
            this(path, takeoffWeightKg, cruiseAltitudeFt, departureElevationFt,
                    destinationElevationFt, offBlocks, StepClimbPolicy.NONE);
        }

        public Request {
            stepClimb = stepClimb == null ? StepClimbPolicy.NONE : stepClimb;
            path = List.copyOf(path);
            if (path.size() < 2) {
                throw new IllegalArgumentException("a route needs at least two points");
            }
            if (takeoffWeightKg <= 0) {
                throw new IllegalArgumentException("takeoff weight must be positive");
            }
            if (cruiseAltitudeFt <= departureElevationFt) {
                throw new IllegalArgumentException("the cruise altitude is below the departure field");
            }
        }
    }

    public FlightProfile compute(Request request) {
        double cruiseAltitude = Math.min(request.cruiseAltitudeFt(), type.climb().ceilingFt());

        VerticalSegment climb = type.climb().between(
                request.departureElevationFt(), cruiseAltitude, request.takeoffWeightKg());
        // The descent table is read at an estimated landing weight. The estimate only has to be
        // close: descent fuel is small and barely moves with weight.
        double estimatedLandingWeight = Math.max(type.weightLimits().operatingEmptyKg(),
                request.takeoffWeightKg() - climb.fuelKg()
                        - estimateCruiseFuel(request, cruiseAltitude, climb));
        VerticalSegment descent = type.descent().between(
                cruiseAltitude, request.destinationElevationFt(), estimatedLandingWeight);

        List<Step> steps = subdivide(request.path());
        double totalDistance = steps.stream().mapToDouble(Step::distanceNm).sum();

        double climbGround = groundDistance(climb, steps.get(0),
                midAltitude(request.departureElevationFt(), cruiseAltitude), request.offBlocks());
        double descentGround = groundDistance(descent, steps.get(steps.size() - 1),
                midAltitude(request.destinationElevationFt(), cruiseAltitude), request.offBlocks());

        // A route too short to climb and descend on gets a proportional share of each, rather
        // than a negative cruise: the profile becomes a climb straight into a descent.
        if (climbGround + descentGround > totalDistance) {
            double scale = totalDistance / (climbGround + descentGround);
            climbGround *= scale;
            descentGround *= scale;
        }

        Geometry geometry = new Geometry(totalDistance, climbGround,
                totalDistance - descentGround, descentGround);
        return integrate(request, steps, cruiseAltitude, climb, descent, geometry);
    }

    // ------------------------------------------------------------------ integration

    private FlightProfile integrate(Request request, List<Step> steps, double cruiseAltitude,
                                    VerticalSegment climb, VerticalSegment descent,
                                    Geometry geometry) {
        List<ProfileLeg> legs = new ArrayList<>(steps.size() + 2);
        double weight = request.takeoffWeightKg();
        double elapsedMinutes = 0.0;
        double cumulativeFuel = 0.0;
        double travelled = 0.0;
        boolean forecastCovered = true;

        // The level currently being cruised at, which a step climb raises.
        double level = cruiseAltitude;
        int stepsTaken = 0;
        double sinceLevelChange = 0.0;

        // A deque rather than a for-loop, because a step climb splits the leg it happens on
        // into the climb itself and the cruise that follows it at the new level.
        Deque<Step> pending = new ArrayDeque<>(splitAtPhaseBoundaries(steps, geometry));

        while (!pending.isEmpty()) {
            Step step = pending.poll();
            double midpointAlong = travelled + step.distanceNm() / 2.0;
            FlightPhase phase = geometry.phaseAt(midpointAlong);
            Instant at = request.offBlocks().plusSeconds(Math.round(elapsedMinutes * 60.0));

            if (phase == FlightPhase.CRUISE) {
                StepClimbPolicy policy = request.stepClimb();
                double remainingCruise = geometry.descentBeginsAtNm() - travelled;
                double candidate = level + policy.incrementFt();
                if (policy.isEnabled()
                        && stepsTaken < policy.maximumSteps()
                        && sinceLevelChange >= policy.minimumRunBeforeStepNm()
                        && remainingCruise >= policy.minimumRunAfterLastStepNm()
                        && candidate <= type.climb().ceilingFt()
                        && paysForItself(step, level, candidate, weight, at, remainingCruise)) {
                    VerticalSegment stepClimb = type.climb().between(level, candidate, weight);
                    Step[] split = carveOut(step,
                            stepClimbGroundNm(stepClimb, step, level, candidate, at));
                    Step climbing = split[0];
                    GeoPoint stepMidpoint = Geodesy.midpoint(climbing.from(), climbing.to());

                    forecastCovered &= weather.covers(stepMidpoint, at);
                    AtmosphericConditions stepAir =
                            weather.at(stepMidpoint, (level + candidate) / 2.0, at);

                    weight -= stepClimb.fuelKg();
                    elapsedMinutes += stepClimb.timeMinutes();
                    cumulativeFuel += stepClimb.fuelKg();
                    travelled += climbing.distanceNm();
                    double speed = stepClimb.timeMinutes() <= 0.0
                            ? 0.0
                            : climbing.distanceNm() / (stepClimb.timeMinutes() / 60.0);
                    legs.add(new ProfileLeg(climbing.from(), climbing.to(), FlightPhase.STEP_CLIMB,
                            climbing.distanceNm(), climbing.trueCourseDeg(), candidate,
                            stepAir.wind(), speed, stepClimb.timeMinutes(), stepClimb.fuelKg(),
                            weight, elapsedMinutes, cumulativeFuel,
                            request.offBlocks().plusSeconds(Math.round(elapsedMinutes * 60.0))));

                    level = candidate;
                    stepsTaken++;
                    sinceLevelChange = 0.0;
                    if (split[1] != null) {
                        pending.addFirst(split[1]);
                    }
                    continue;
                }
            }

            double altitude = phase == FlightPhase.CRUISE
                    ? level
                    : altitudeFor(phase, request, level, midpointAlong, geometry);

            GeoPoint midpoint = Geodesy.midpoint(step.from(), step.to());
            forecastCovered &= weather.covers(midpoint, at);
            AtmosphericConditions air = weather.at(midpoint, altitude, at);

            LegResult result = switch (phase) {
                case CRUISE -> cruiseLeg(step, altitude, air, weight);
                case CLIMB -> verticalLeg(step, climb, geometry.climbGroundNm());
                case DESCENT -> verticalLeg(step, descent, geometry.descentGroundNm());
                case STEP_CLIMB -> throw new IllegalStateException(
                        "a step climb is emitted directly, never reached through the phase map");
            };

            weight -= result.fuelKg();
            elapsedMinutes += result.timeMinutes();
            cumulativeFuel += result.fuelKg();
            travelled += step.distanceNm();
            sinceLevelChange += step.distanceNm();

            legs.add(new ProfileLeg(step.from(), step.to(), phase, step.distanceNm(),
                    step.trueCourseDeg(), altitude, air.wind(), result.groundSpeedKt(),
                    result.timeMinutes(), result.fuelKg(), weight, elapsedMinutes, cumulativeFuel,
                    request.offBlocks().plusSeconds(Math.round(elapsedMinutes * 60.0))));
        }

        return new FlightProfile(legs, request.takeoffWeightKg(), cumulativeFuel, elapsedMinutes,
                cruiseAltitude,
                request.offBlocks().plusSeconds(Math.round(elapsedMinutes * 60.0)),
                forecastCovered);
    }

    /**
     * Whether stepping to the higher level saves fuel over the cruise that is left.
     *
     * <p>This is the whole decision, and it is a comparison of two totals rather than of two
     * specific ranges: flying the rest of the cruise at the present level, against the fuel the
     * step burns plus the rest of the cruise at the new one. A step that improves the specific
     * range but is taken too late to earn back what the climb cost fails this test, which is
     * why the test is written on fuel and not on efficiency.
     *
     * <p>Both sides are measured against the ground, so the wind is in the answer. A higher
     * level with a worse wind can lose, and it should.
     */
    private boolean paysForItself(Step step, double level, double candidate, double weightKg,
                                  Instant at, double remainingCruiseNm) {
        if (remainingCruiseNm <= 0.0) {
            return false;
        }
        GeoPoint midpoint = Geodesy.midpoint(step.from(), step.to());
        double atPresent = cruiseFuelOver(remainingCruiseNm, step, level, weightKg, at, midpoint);
        double stepFuel = type.climb().between(level, candidate, weightKg).fuelKg();
        double atCandidate = stepFuel + cruiseFuelOver(
                remainingCruiseNm, step, candidate, weightKg - stepFuel, at, midpoint);
        return atCandidate < atPresent;
    }

    /** The fuel to cover a ground distance at a level, at the wind and weight given. */
    private double cruiseFuelOver(double distanceNm, Step step, double altitudeFt, double weightKg,
                                  Instant at, GeoPoint where) {
        AtmosphericConditions air = weather.at(where, altitudeFt, at);
        double trueAirspeed = type.cruise().trueAirspeedKt(weightKg, altitudeFt, air);
        double groundSpeed = groundSpeed(step.trueCourseDeg(), trueAirspeed, air.wind());
        if (groundSpeed <= 0.0) {
            return Double.MAX_VALUE;
        }
        return type.cruise().fuelFlowKgPerHour(weightKg, altitudeFt, air)
                * (distanceNm / groundSpeed);
    }

    /** The ground a step climb covers: its still-air distance, flown against the wind. */
    private double stepClimbGroundNm(VerticalSegment stepClimb, Step step, double level,
                                     double candidate, Instant at) {
        double stillAirSpeed = stepClimb.averageSpeedKt();
        if (stepClimb.distanceNm() <= 0.0 || stillAirSpeed <= 0.0) {
            return 0.0;
        }
        WindVector wind = weather.at(Geodesy.midpoint(step.from(), step.to()),
                (level + candidate) / 2.0, at).wind();
        double groundSpeed = groundSpeed(step.trueCourseDeg(), stillAirSpeed, wind);
        return Math.max(0.0, stepClimb.distanceNm() * groundSpeed / stillAirSpeed);
    }

    /**
     * Splits a step so its first part covers {@code firstNm}. The second element is null when
     * the step is shorter than that, in which case the whole step becomes the first part — a
     * step climb that would outrun its leg is charged to that leg rather than spilling into the
     * next one, whose course may differ.
     */
    private static Step[] carveOut(Step step, double firstNm) {
        if (firstNm <= 0.0 || firstNm >= step.distanceNm()) {
            return new Step[] {step, null};
        }
        GeoPoint at = Geodesy.intermediatePoint(step.from(), step.to(), firstNm / step.distanceNm());
        return new Step[] {
                new Step(step.from(), at, Geodesy.distanceNm(step.from(), at), step.trueCourseDeg()),
                new Step(at, step.to(), Geodesy.distanceNm(at, step.to()),
                        Geodesy.initialBearingDeg(at, step.to()))};
    }

    /**
     * A cruise leg, integrated with one corrector pass. The first pass burns the leg at the
     * entry weight; the second re-reads the tables at the mean weight over the leg, which is
     * where the average burn actually sits.
     */
    private LegResult cruiseLeg(Step step, double altitudeFt, AtmosphericConditions air, double weightKg) {
        LegResult predicted = cruiseStep(step, altitudeFt, air, weightKg);
        return cruiseStep(step, altitudeFt, air, weightKg - predicted.fuelKg() / 2.0);
    }

    private LegResult cruiseStep(Step step, double altitudeFt, AtmosphericConditions air, double weightKg) {
        double trueAirspeed = type.cruise().trueAirspeedKt(weightKg, altitudeFt, air);
        double groundSpeed = groundSpeed(step.trueCourseDeg(), trueAirspeed, air.wind());
        double timeMinutes = groundSpeed <= 0.0 ? 0.0 : step.distanceNm() / groundSpeed * 60.0;
        double fuel = type.cruise().fuelFlowKgPerHour(weightKg, altitudeFt, air) * timeMinutes / 60.0;
        return new LegResult(groundSpeed, timeMinutes, fuel);
    }

    /**
     * A climb or descent leg takes the share of the phase's time and fuel that its distance is
     * of the phase's ground distance. The ground speed follows from the two rather than being
     * computed independently, so a leg's distance, time and speed always agree.
     */
    private static LegResult verticalLeg(Step step, VerticalSegment segment, double phaseGroundNm) {
        if (phaseGroundNm <= 0.0 || segment.timeMinutes() <= 0.0) {
            return new LegResult(0.0, 0.0, 0.0);
        }
        double fraction = Math.min(1.0, step.distanceNm() / phaseGroundNm);
        double timeMinutes = segment.timeMinutes() * fraction;
        double groundSpeed = timeMinutes <= 0.0 ? 0.0 : step.distanceNm() / (timeMinutes / 60.0);
        return new LegResult(groundSpeed, timeMinutes, segment.fuelKg() * fraction);
    }

    /**
     * Converts a phase's still-air distance into the ground distance it actually covers, using
     * the wind at the phase's mid-altitude on the course it is flown.
     */
    private double groundDistance(VerticalSegment segment, Step step, double midAltitudeFt,
                                  Instant at) {
        double stillAirSpeed = segment.averageSpeedKt();
        if (segment.distanceNm() <= 0.0 || stillAirSpeed <= 0.0) {
            return 0.0;
        }
        WindVector wind = weather.at(Geodesy.midpoint(step.from(), step.to()), midAltitudeFt, at).wind();
        double groundSpeed = groundSpeed(step.trueCourseDeg(), stillAirSpeed, wind);
        return Math.max(0.0, segment.distanceNm() * groundSpeed / stillAirSpeed);
    }

    /**
     * Ground speed for a leg, falling back to the still-air speed adjusted by the along-track
     * component when the crosswind is so strong that no heading holds the track. That case is
     * physically real — it happens on a short leg across a jet stream core — and refusing to
     * produce a profile over it would be worse than a leg whose speed is approximate.
     */
    private static double groundSpeed(double trueCourseDeg, double trueAirspeedKt, WindVector wind) {
        try {
            return WindTriangle.groundSpeedKt(trueCourseDeg, trueAirspeedKt, wind);
        } catch (WindTriangle.UnsolvableWindException windTooStrong) {
            return trueAirspeedKt + wind.tailwindComponentKt(trueCourseDeg);
        }
    }

    // ------------------------------------------------------------------ geometry

    /** Where along the route each phase begins and ends. */
    private record Geometry(double totalNm, double climbGroundNm, double descentBeginsAtNm,
                            double descentGroundNm) {

        FlightPhase phaseAt(double distanceAlongNm) {
            if (distanceAlongNm <= climbGroundNm) {
                return FlightPhase.CLIMB;
            }
            return distanceAlongNm >= descentBeginsAtNm ? FlightPhase.DESCENT : FlightPhase.CRUISE;
        }
    }

    /** Splits the path into steps no longer than {@link #MAX_STEP_NM}. */
    private static List<Step> subdivide(List<GeoPoint> path) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            GeoPoint from = path.get(i);
            GeoPoint to = path.get(i + 1);
            double distance = Geodesy.distanceNm(from, to);
            if (distance <= 0.0) {
                continue;
            }
            int pieces = (int) Math.ceil(distance / MAX_STEP_NM);
            GeoPoint previous = from;
            for (int piece = 1; piece <= pieces; piece++) {
                GeoPoint next = piece == pieces
                        ? to
                        : Geodesy.intermediatePoint(from, to, (double) piece / pieces);
                steps.add(new Step(previous, next, Geodesy.distanceNm(previous, next),
                        Geodesy.initialBearingDeg(previous, next)));
                previous = next;
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("the route has no length");
        }
        return steps;
    }

    /**
     * Splits any step that straddles the top of climb or the top of descent, so that no leg is
     * half one phase and half another.
     */
    private static List<Step> splitAtPhaseBoundaries(List<Step> steps, Geometry geometry) {
        List<Step> split = new ArrayList<>(steps.size() + 2);
        double travelled = 0.0;
        for (Step step : steps) {
            double start = travelled;
            double end = travelled + step.distanceNm();
            Double boundary = null;
            if (start < geometry.climbGroundNm() && end > geometry.climbGroundNm()) {
                boundary = geometry.climbGroundNm();
            } else if (start < geometry.descentBeginsAtNm() && end > geometry.descentBeginsAtNm()) {
                boundary = geometry.descentBeginsAtNm();
            }
            if (boundary == null) {
                split.add(step);
            } else {
                double fraction = (boundary - start) / step.distanceNm();
                GeoPoint at = Geodesy.intermediatePoint(step.from(), step.to(), fraction);
                split.add(new Step(step.from(), at, Geodesy.distanceNm(step.from(), at),
                        step.trueCourseDeg()));
                split.add(new Step(at, step.to(), Geodesy.distanceNm(at, step.to()),
                        Geodesy.initialBearingDeg(at, step.to())));
            }
            travelled = end;
        }
        return split;
    }

    /** The altitude to sample the wind at: the phase's mid-altitude in a climb or descent. */
    private static double altitudeFor(FlightPhase phase, Request request, double cruiseAltitude,
                                      double distanceAlongNm, Geometry geometry) {
        return switch (phase) {
            case CRUISE, STEP_CLIMB -> cruiseAltitude;
            case CLIMB -> geometry.climbGroundNm() <= 0.0
                    ? cruiseAltitude
                    : interpolate(request.departureElevationFt(), cruiseAltitude,
                    distanceAlongNm / geometry.climbGroundNm());
            case DESCENT -> geometry.descentGroundNm() <= 0.0
                    ? cruiseAltitude
                    : interpolate(cruiseAltitude, request.destinationElevationFt(),
                    (distanceAlongNm - geometry.descentBeginsAtNm()) / geometry.descentGroundNm());
        };
    }

    private static double midAltitude(double fieldElevationFt, double cruiseAltitudeFt) {
        return (fieldElevationFt + cruiseAltitudeFt) / 2.0;
    }

    private static double interpolate(double from, double to, double fraction) {
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        return from + (to - from) * clamped;
    }

    /**
     * A first guess at the cruise fuel, used only to pick the weight the descent table is read
     * at. It assumes still air and the cruise fuel flow at the takeoff weight, which is close
     * enough for a table whose values barely move with weight.
     */
    private double estimateCruiseFuel(Request request, double cruiseAltitude, VerticalSegment climb) {
        double totalDistance = 0.0;
        List<GeoPoint> path = request.path();
        for (int i = 0; i < path.size() - 1; i++) {
            totalDistance += Geodesy.distanceNm(path.get(i), path.get(i + 1));
        }
        double cruiseDistance = Math.max(0.0, totalDistance - climb.distanceNm());
        AtmosphericConditions air = AtmosphericConditions.isa(cruiseAltitude);
        double speed = type.cruise().trueAirspeedKt(request.takeoffWeightKg(), cruiseAltitude, air);
        double hours = speed <= 0.0 ? 0.0 : cruiseDistance / speed;
        return type.cruise().fuelFlowKgPerHour(request.takeoffWeightKg(), cruiseAltitude, air) * hours;
    }

    private record Step(GeoPoint from, GeoPoint to, double distanceNm, double trueCourseDeg) {
    }

    private record LegResult(double groundSpeedKt, double timeMinutes, double fuelKg) {
    }
}
