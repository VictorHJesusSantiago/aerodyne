package io.aerodyne.performance;

/**
 * What a climb or descent between two altitudes costs.
 *
 * @param timeMinutes how long it takes
 * @param fuelKg      what it burns
 * @param distanceNm  the ground the aircraft covers doing it, in still air
 */
public record VerticalSegment(double timeMinutes, double fuelKg, double distanceNm) {

    public static final VerticalSegment NONE = new VerticalSegment(0.0, 0.0, 0.0);

    public double timeHours() {
        return timeMinutes / 60.0;
    }

    /** Average still-air ground speed over the segment, which the wind is then applied to. */
    public double averageSpeedKt() {
        return timeMinutes <= 0.0 ? 0.0 : distanceNm / timeHours();
    }

    public VerticalSegment plus(VerticalSegment other) {
        return new VerticalSegment(timeMinutes + other.timeMinutes,
                fuelKg + other.fuelKg, distanceNm + other.distanceNm);
    }
}
