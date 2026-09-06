package io.aerodyne.performance;

/**
 * Climb or descent performance, tabulated cumulatively from sea level.
 *
 * <p>This is how published climb tables work, and it is not an accident of presentation: the
 * cost of climbing from 10 000 ft to 35 000 ft is the cost of reaching 35 000 ft minus the cost
 * of reaching 10 000 ft, and tabulating the cumulative figure lets one table answer for every
 * pair of altitudes. {@link #between} does that subtraction.
 *
 * <p>Weight is the table's other axis, and a climb changes it: the aircraft is lighter at the
 * top than at the bottom. The tables are indexed on the weight at the <em>start</em> of the
 * climb, which is the convention published tables use and which errs in the conservative
 * direction — a heavier aircraft climbs worse, so reading against the start weight cannot
 * under-predict the fuel.
 *
 * @param timeTable     cumulative minutes from sea level
 * @param fuelTable     cumulative kilograms from sea level
 * @param distanceTable cumulative still-air nautical miles from sea level
 */
public record VerticalPerformance(PerformanceTable timeTable,
                                  PerformanceTable fuelTable,
                                  PerformanceTable distanceTable) {

    /**
     * What it costs to go from one altitude to another at a given start weight.
     *
     * <p>A segment that does not move — the same altitude at both ends — costs nothing. A
     * descent table is tabulated the same way as a climb one, so {@code between(35000, 0, w)}
     * on a descent table gives a positive cost; the sign of the altitude change does not change
     * the sign of the answer.
     */
    public VerticalSegment between(double fromAltitudeFt, double toAltitudeFt, double weightKg) {
        if (fromAltitudeFt == toAltitudeFt) {
            return VerticalSegment.NONE;
        }
        double low = Math.min(fromAltitudeFt, toAltitudeFt);
        double high = Math.max(fromAltitudeFt, toAltitudeFt);

        return new VerticalSegment(
                Math.max(0.0, timeTable.valueAt(weightKg, high) - timeTable.valueAt(weightKg, low)),
                Math.max(0.0, fuelTable.valueAt(weightKg, high) - fuelTable.valueAt(weightKg, low)),
                Math.max(0.0, distanceTable.valueAt(weightKg, high)
                        - distanceTable.valueAt(weightKg, low)));
    }

    /** The cost of reaching an altitude from sea level. */
    public VerticalSegment fromSeaLevelTo(double altitudeFt, double weightKg) {
        return between(0.0, altitudeFt, weightKg);
    }

    /** The highest altitude the table covers, which caps a requested cruise level. */
    public double ceilingFt() {
        return timeTable.maximumAltitudeFt();
    }
}
