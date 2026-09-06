package io.aerodyne.performance;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.weather.WindVector;

import java.time.Instant;

/**
 * One integrated leg of a flight profile: what happened between two points on the route.
 *
 * @param from             where the leg starts
 * @param to               where it ends
 * @param phase            climb, cruise or descent
 * @param distanceNm       the leg's length along the ground
 * @param trueCourseDeg    the course flown
 * @param altitudeFt       the pressure altitude flown; the mid-altitude of a climb or descent
 * @param wind             the wind used, sampled at the leg's midpoint and altitude
 * @param groundSpeedKt    the resulting ground speed
 * @param timeMinutes      how long the leg took
 * @param fuelKg           what it burned
 * @param weightAtEndKg    the aircraft's weight on reaching {@code to}
 * @param elapsedMinutes   time since takeoff on reaching {@code to}
 * @param cumulativeFuelKg fuel burned since takeoff on reaching {@code to}
 * @param arrivesAt        the wall-clock time of reaching {@code to}
 */
public record ProfileLeg(GeoPoint from,
                         GeoPoint to,
                         FlightPhase phase,
                         double distanceNm,
                         double trueCourseDeg,
                         double altitudeFt,
                         WindVector wind,
                         double groundSpeedKt,
                         double timeMinutes,
                         double fuelKg,
                         double weightAtEndKg,
                         double elapsedMinutes,
                         double cumulativeFuelKg,
                         Instant arrivesAt) {

    /** The wind's component along the leg, positive as a headwind — the OFP's wind column. */
    public double headwindComponentKt() {
        return wind.headwindComponentKt(trueCourseDeg);
    }
}
