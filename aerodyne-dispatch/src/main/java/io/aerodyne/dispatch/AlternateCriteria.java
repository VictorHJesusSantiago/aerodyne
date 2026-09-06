package io.aerodyne.dispatch;

/**
 * What an aerodrome has to satisfy to be usable as an alternate.
 *
 * <p>Alternate planning minima are deliberately higher than the minima for landing there. The
 * alternate is chosen hours ahead on a forecast, and the forecast has to be wrong by a good
 * margin before the aerodrome becomes unusable — so the aerodrome has to be forecast comfortably
 * better than the minimum the crew could actually land on, not merely at it.
 *
 * @param maxDistanceNm             how far from the destination an alternate may be
 * @param minimumRunwayFt           the shortest runway the aircraft can use
 * @param requiredCeilingFt         the ceiling the forecast must show at the arrival time
 * @param requiredVisibilityMetres  the visibility it must show
 * @param requirePrecisionApproach  whether an instrument landing system is required
 * @param requireCurrentWeather     whether an aerodrome with no usable report may be considered
 */
public record AlternateCriteria(double maxDistanceNm,
                                int minimumRunwayFt,
                                int requiredCeilingFt,
                                int requiredVisibilityMetres,
                                boolean requirePrecisionApproach,
                                boolean requireCurrentWeather) {

    public AlternateCriteria {
        if (maxDistanceNm <= 0) {
            throw new IllegalArgumentException("the search radius must be positive");
        }
    }

    /**
     * Typical planning minima for a jet with a precision approach available: 200 nm, 6000 ft of
     * runway, a 600 ft ceiling and 3000 m visibility.
     */
    public static AlternateCriteria forJetWithPrecisionApproach() {
        return new AlternateCriteria(200.0, 6_000, 600, 3_000, true, true);
    }

    /**
     * The higher minima that apply where only a non-precision approach is available: the
     * aerodrome has to be forecast better still, because the approach itself gets you less far
     * down.
     */
    public static AlternateCriteria forNonPrecisionApproach() {
        return new AlternateCriteria(200.0, 6_000, 800, 4_000, false, true);
    }

    /** The same criteria with a different search radius. */
    public AlternateCriteria withinNm(double newMaxDistanceNm) {
        return new AlternateCriteria(newMaxDistanceNm, minimumRunwayFt, requiredCeilingFt,
                requiredVisibilityMetres, requirePrecisionApproach, requireCurrentWeather);
    }

    /** The same criteria with a different runway requirement. */
    public AlternateCriteria withMinimumRunwayFt(int newMinimumRunwayFt) {
        return new AlternateCriteria(maxDistanceNm, newMinimumRunwayFt, requiredCeilingFt,
                requiredVisibilityMetres, requirePrecisionApproach, requireCurrentWeather);
    }
}
