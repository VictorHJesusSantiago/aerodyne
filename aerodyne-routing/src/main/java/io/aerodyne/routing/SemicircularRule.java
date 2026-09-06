package io.aerodyne.routing;

import io.aerodyne.geo.Geodesy;

import java.util.ArrayList;
import java.util.List;

/**
 * Which cruising levels a track may use.
 *
 * <p>The semicircular rule keeps opposite-direction traffic vertically separated by giving each
 * half of the compass its own set of levels: eastbound tracks fly odd thousands, westbound
 * tracks even ones. "Eastbound" means a <em>magnetic</em> track from 000 to 179 degrees, and the
 * magnetic part matters — near the agonic line it makes no difference, but over northern Canada
 * a true track of 010 can be a magnetic track of 040 and the rule follows the magnetic one.
 *
 * <p>Three bands behave differently:
 *
 * <ul>
 *   <li><b>Below FL290</b> — odd and even thousands, 1000 ft apart.</li>
 *   <li><b>FL290 to FL410</b> — the RVSM band, still 1000 ft apart, which is what RVSM
 *       approval buys: without it the spacing here would be 2000 ft.</li>
 *   <li><b>Above FL410</b> — 4000 ft apart, because pressure-based altimetry is too coarse up
 *       there for anything closer. The eastbound levels are FL450, FL490 and so on.</li>
 * </ul>
 *
 * <p>Some airspace inverts the rule — parts of Europe and the North Atlantic organised track
 * system assign levels by other means — so this gives what the general rule allows, and an
 * airspace-specific allocation overrides it.
 */
public final class SemicircularRule {

    /** The lowest level of the reduced vertical separation band. */
    public static final int RVSM_LOWER_FL = 290;
    /** The highest level of it. */
    public static final int RVSM_UPPER_FL = 410;

    private SemicircularRule() {
    }

    /** True for a magnetic track from 000 to 179 degrees, which flies the odd levels. */
    public static boolean isEastbound(double magneticTrackDeg) {
        double track = Geodesy.normaliseBearing(magneticTrackDeg);
        return track < 180.0;
    }

    /** True when a flight level is available to a track under the general rule. */
    public static boolean isAvailable(double magneticTrackDeg, int flightLevel) {
        if (flightLevel <= 0) {
            return false;
        }
        boolean eastbound = isEastbound(magneticTrackDeg);
        if (flightLevel <= RVSM_UPPER_FL) {
            if (flightLevel % 10 != 0) {
                return false;
            }
            boolean odd = (flightLevel / 10) % 2 == 1;
            return odd == eastbound;
        }
        // Above the RVSM band the levels step by 4000 ft: 430, 470, 510 westbound and
        // 450, 490, 530 eastbound.
        int base = eastbound ? 450 : 430;
        return flightLevel >= base && (flightLevel - base) % 40 == 0;
    }

    /** Every level available to a track between two bounds, lowest first. */
    public static List<Integer> availableLevels(double magneticTrackDeg,
                                                int lowestFlightLevel,
                                                int highestFlightLevel) {
        List<Integer> levels = new ArrayList<>();
        for (int level = Math.max(10, lowestFlightLevel); level <= highestFlightLevel; level += 10) {
            if (isAvailable(magneticTrackDeg, level)) {
                levels.add(level);
            }
        }
        return List.copyOf(levels);
    }

    /**
     * The available level closest to one the flight would rather have. Ties go to the higher
     * level, which is where the specific range is better.
     *
     * @return the chosen level, or empty when the bounds contain no available level at all
     */
    public static java.util.OptionalInt nearestAvailableLevel(double magneticTrackDeg,
                                                              int desiredFlightLevel,
                                                              int lowestFlightLevel,
                                                              int highestFlightLevel) {
        List<Integer> levels = availableLevels(magneticTrackDeg, lowestFlightLevel, highestFlightLevel);
        if (levels.isEmpty()) {
            return java.util.OptionalInt.empty();
        }
        int best = levels.get(0);
        for (int level : levels) {
            int currentGap = Math.abs(best - desiredFlightLevel);
            int candidateGap = Math.abs(level - desiredFlightLevel);
            if (candidateGap < currentGap || (candidateGap == currentGap && level > best)) {
                best = level;
            }
        }
        return java.util.OptionalInt.of(best);
    }

    /**
     * The next level up that the same track may use — the step climb a flight takes as it burns
     * off weight. Two thousand feet in the ordinary bands, four above FL410.
     */
    public static java.util.OptionalInt nextLevelUp(double magneticTrackDeg, int currentFlightLevel,
                                                    int highestFlightLevel) {
        for (int level = currentFlightLevel + 10; level <= highestFlightLevel; level += 10) {
            if (isAvailable(magneticTrackDeg, level)) {
                return java.util.OptionalInt.of(level);
            }
        }
        return java.util.OptionalInt.empty();
    }

    /** True when a level needs reduced vertical separation approval to be flown. */
    public static boolean requiresRvsmApproval(int flightLevel) {
        return flightLevel >= RVSM_LOWER_FL && flightLevel <= RVSM_UPPER_FL;
    }
}
