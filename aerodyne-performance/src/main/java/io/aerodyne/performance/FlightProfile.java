package io.aerodyne.performance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A flight integrated end to end: every leg with its wind, ground speed, time and fuel, and the
 * totals that go on the front page of the OFP.
 *
 * @param legs             the legs in order, from takeoff to landing
 * @param takeoffWeightKg  the weight the integration started from
 * @param tripFuelKg       total fuel from takeoff to landing
 * @param tripTimeMinutes  total time from takeoff to landing
 * @param cruiseAltitudeFt the level the cruise was flown at
 * @param arrivesAt        the estimated time of arrival
 * @param usedForecastWind whether the winds came from a forecast or from the fallback model
 */
public record FlightProfile(List<ProfileLeg> legs,
                            double takeoffWeightKg,
                            double tripFuelKg,
                            double tripTimeMinutes,
                            double cruiseAltitudeFt,
                            Instant arrivesAt,
                            boolean usedForecastWind) {

    public FlightProfile {
        legs = List.copyOf(legs);
    }

    public double landingWeightKg() {
        return takeoffWeightKg - tripFuelKg;
    }

    public double distanceNm() {
        return legs.stream().mapToDouble(ProfileLeg::distanceNm).sum();
    }

    public Duration tripTime() {
        return Duration.ofSeconds(Math.round(tripTimeMinutes * 60.0));
    }

    /** The distance flown in one phase, which the OFP breaks out separately. */
    public double distanceNm(FlightPhase phase) {
        return legs.stream()
                .filter(leg -> leg.phase() == phase)
                .mapToDouble(ProfileLeg::distanceNm)
                .sum();
    }

    public double fuelKg(FlightPhase phase) {
        return legs.stream()
                .filter(leg -> leg.phase() == phase)
                .mapToDouble(ProfileLeg::fuelKg)
                .sum();
    }

    /**
     * The levels the cruise was flown at, lowest first: one entry for a level cruise, one more
     * for each step climb taken.
     */
    public List<Double> cruiseLevelsFt() {
        List<Double> levels = new java.util.ArrayList<>();
        for (ProfileLeg leg : legs) {
            if (!leg.phase().isCruise()) {
                continue;
            }
            if (levels.isEmpty() || levels.get(levels.size() - 1) != leg.altitudeFt()) {
                levels.add(leg.altitudeFt());
            }
        }
        return List.copyOf(levels);
    }

    /** How many step climbs the profile plans. */
    public int stepClimbCount() {
        return (int) legs.stream().filter(leg -> leg.phase() == FlightPhase.STEP_CLIMB).count();
    }

    /** The level the aircraft arrives at the top of descent on, which is what ATC is asked for. */
    public double finalCruiseLevelFt() {
        List<Double> levels = cruiseLevelsFt();
        return levels.isEmpty() ? cruiseAltitudeFt : levels.get(levels.size() - 1);
    }

    /**
     * The average headwind over the whole route, weighted by distance. This is the single
     * number a crew uses to sanity-check a plan against the last time they flew it.
     */
    public double averageHeadwindKt() {
        double distance = distanceNm();
        if (distance <= 0.0) {
            return 0.0;
        }
        return legs.stream()
                .mapToDouble(leg -> leg.headwindComponentKt() * leg.distanceNm())
                .sum() / distance;
    }

    /** Average ground speed over the trip. */
    public double averageGroundSpeedKt() {
        return tripTimeMinutes <= 0.0 ? 0.0 : distanceNm() / (tripTimeMinutes / 60.0);
    }
}
