package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;

import java.util.List;
import java.util.Optional;

/**
 * What an ETOPS assessment of a route found.
 *
 * <p>A twin-engine aeroplane may not ordinarily be more than sixty minutes from an adequate
 * aerodrome. An ETOPS approval extends that — to 120, 180, 330 minutes — and a route planned
 * under one has to be shown to stay inside the approved time for its whole length. This is the
 * result of that demonstration.
 *
 * <p>Three positions come out of it. The <b>entry point</b> is where the route first leaves the
 * sixty-minute area and ETOPS rules begin to apply; the <b>exit point</b> is where it re-enters
 * one. Between them lies the <b>critical point</b>: the place where the route is furthest, in
 * time, from any adequate aerodrome, and the number that decides whether the route is legal.
 *
 * @param approvedMinutes  the diversion time the operator is approved for
 * @param thresholdMinutes the time beyond which ETOPS rules apply, ordinarily sixty
 * @param entryPoint       where the route leaves the threshold area, empty when it never does
 * @param exitPoint        where it re-enters one, empty when the route never left
 * @param worstPoint       the point furthest in time from any adequate aerodrome
 * @param worstDiversionMinutes the diversion time at that point
 * @param worstNearestIcao the aerodrome that is nearest to it
 * @param adequateAerodromes the aerodromes the assessment was made against
 * @param equalTimePoints  the equal-time points between successive adequate aerodromes
 * @param weatherScreened  whether the aerodromes were screened against their forecasts, or only
 *                         on runway length; a route assessed the second way understates its
 *                         diversion times, so this reaches the dispatch decision rather than
 *                         staying in a log line
 */
public record EtopsAnalysis(int approvedMinutes,
                            int thresholdMinutes,
                            Optional<GeoPoint> entryPoint,
                            Optional<GeoPoint> exitPoint,
                            GeoPoint worstPoint,
                            double worstDiversionMinutes,
                            String worstNearestIcao,
                            List<String> adequateAerodromes,
                            List<EqualTimePoint> equalTimePoints,
                            boolean weatherScreened) {

    /** An assessment made before the aerodromes could be screened against their forecasts. */
    public EtopsAnalysis(int approvedMinutes, int thresholdMinutes, Optional<GeoPoint> entryPoint,
                         Optional<GeoPoint> exitPoint, GeoPoint worstPoint,
                         double worstDiversionMinutes, String worstNearestIcao,
                         List<String> adequateAerodromes, List<EqualTimePoint> equalTimePoints) {
        this(approvedMinutes, thresholdMinutes, entryPoint, exitPoint, worstPoint,
                worstDiversionMinutes, worstNearestIcao, adequateAerodromes, equalTimePoints,
                false);
    }

    public EtopsAnalysis {
        adequateAerodromes = List.copyOf(adequateAerodromes);
        equalTimePoints = List.copyOf(equalTimePoints);
    }

    /** True when the route never exceeds the approved diversion time. */
    public boolean isCompliant() {
        return worstDiversionMinutes <= approvedMinutes;
    }

    /**
     * True when the route stays inside the ordinary threshold throughout, so no ETOPS approval
     * is needed to fly it at all.
     */
    public boolean isWithinThreshold() {
        return entryPoint.isEmpty();
    }

    /** How much of the approval is left unused at the worst point, in minutes. */
    public double marginMinutes() {
        return approvedMinutes - worstDiversionMinutes;
    }

    /**
     * The diversion time the route would need to be approved for, rounded up to the approval
     * steps operators actually hold. Useful when a route fails: it says what would fly it.
     */
    public int requiredApprovalMinutes() {
        int[] steps = {60, 90, 120, 138, 180, 207, 240, 330};
        for (int step : steps) {
            if (worstDiversionMinutes <= step) {
                return step;
            }
        }
        return (int) Math.ceil(worstDiversionMinutes);
    }

    /** A one-line summary for the OFP header. */
    public String summary() {
        String screening = weatherScreened ? "" : " (runway length only)";
        if (isWithinThreshold()) {
            return "Route stays within " + thresholdMinutes
                    + " minutes of an adequate aerodrome" + screening;
        }
        return String.format("ETOPS %d: worst diversion %.0f min to %s, margin %.0f min%s%s",
                approvedMinutes, worstDiversionMinutes, worstNearestIcao, marginMinutes(),
                screening, isCompliant() ? "" : " — NOT COMPLIANT");
    }
}
