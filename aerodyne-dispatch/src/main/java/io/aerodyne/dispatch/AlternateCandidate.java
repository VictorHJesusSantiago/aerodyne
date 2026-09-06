package io.aerodyne.dispatch;

import io.aerodyne.navdata.Airport;
import io.aerodyne.weather.metar.SurfaceConditions;

import java.util.List;
import java.util.Optional;

/**
 * An aerodrome considered as an alternate, and what was found against it.
 *
 * <p>Rejected candidates are kept rather than discarded. When a flight ends up with no legal
 * alternate the useful question is what was wrong with the near ones — a runway 200 ft short, a
 * forecast 100 ft below minima — and that is only answerable if the reasons survived the
 * screening.
 *
 * @param airport       the aerodrome
 * @param distanceNm    how far it is from the point it was searched around
 * @param bearingDeg    the true bearing to it
 * @param conditions    the weather the decision was made on, when there was any
 * @param rejections    why it cannot be used; empty when it can
 */
public record AlternateCandidate(Airport airport,
                                 double distanceNm,
                                 double bearingDeg,
                                 SurfaceConditions conditions,
                                 List<String> rejections) {

    public AlternateCandidate {
        rejections = List.copyOf(rejections);
    }

    public String icaoCode() {
        return airport.icaoCode();
    }

    /** True when nothing was found against the aerodrome. */
    public boolean isUsable() {
        return rejections.isEmpty();
    }

    public Optional<SurfaceConditions> weather() {
        return Optional.ofNullable(conditions);
    }

    /** The ceiling the decision was made on, when the forecast gave one. */
    public java.util.OptionalInt ceilingFt() {
        return conditions == null ? java.util.OptionalInt.empty() : conditions.ceilingFt();
    }

    /** One line for the OFP's alternate table. */
    public String summary() {
        String verdict = isUsable() ? "usable" : String.join("; ", rejections);
        return String.format("%s %3.0f nm/%03.0f — %s",
                icaoCode(), distanceNm, bearingDeg, verdict);
    }

    @Override
    public String toString() {
        return summary();
    }
}
