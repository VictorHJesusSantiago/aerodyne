package io.aerodyne.weather.metar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A decoded terminal aerodrome forecast.
 *
 * <p>A TAF is not a series of independent forecasts: the body states what is expected from the
 * start of the validity period, and each subsequent group modifies it. {@link #prevailingAt}
 * and {@link #worstCaseAt} are the two readings dispatch actually needs, and they differ:
 *
 * <ul>
 *   <li><b>Prevailing</b> — the body as amended by every {@code FM} and {@code BECMG} group
 *       that has taken effect. This is what the flight is planned to arrive into.</li>
 *   <li><b>Worst case</b> — the prevailing conditions further degraded by any {@code TEMPO} or
 *       {@code PROB} group active at that moment. This is what an alternate is held against,
 *       because a fluctuation the forecast admits to is a fluctuation that may happen.</li>
 * </ul>
 *
 * @param stationIcao the aerodrome the forecast is for
 * @param issuedAt    when the forecast was issued
 * @param validFrom   the start of the validity period
 * @param validTo     the end of it
 * @param groups      the body and every change group, in order, the body first
 * @param amended     the {@code AMD} flag
 * @param corrected   the {@code COR} flag
 * @param cancelled   the {@code CNL} flag: the forecast has been withdrawn and forecasts nothing
 * @param raw         the forecast as received
 */
public record TafReport(String stationIcao,
                        Instant issuedAt,
                        Instant validFrom,
                        Instant validTo,
                        List<TafGroup> groups,
                        boolean amended,
                        boolean corrected,
                        boolean cancelled,
                        String raw) {

    public TafReport {
        groups = List.copyOf(groups);
    }

    /** True when the forecast covers an instant. */
    public boolean covers(Instant when) {
        return !cancelled && !when.isBefore(validFrom) && when.isBefore(validTo);
    }

    /**
     * The prevailing forecast at an instant: the body as replaced by the latest {@code FM} or
     * {@code BECMG} group in effect. Empty when the forecast does not cover the instant.
     */
    public Optional<SurfaceConditions> prevailingAt(Instant when) {
        if (!covers(when)) {
            return Optional.empty();
        }
        SurfaceConditions prevailing = null;
        for (TafGroup group : groups) {
            if (group.changeType().replacesPrevailing() && !when.isBefore(group.validFrom())) {
                prevailing = group.conditions();
            }
        }
        return Optional.ofNullable(prevailing);
    }

    /**
     * The prevailing forecast degraded by any fluctuation group active at the instant.
     *
     * <p>"Degraded" is taken element by element: the lower ceiling, the lower visibility, the
     * stronger wind, the union of the weather. A {@code TEMPO} group that only mentions the
     * visibility does not thereby cancel the prevailing cloud.
     */
    public Optional<SurfaceConditions> worstCaseAt(Instant when) {
        Optional<SurfaceConditions> prevailing = prevailingAt(when);
        if (prevailing.isEmpty()) {
            return prevailing;
        }
        SurfaceConditions worst = prevailing.get();
        for (TafGroup group : groups) {
            if (!group.changeType().replacesPrevailing() && group.appliesAt(when)) {
                worst = worse(worst, group.conditions());
            }
        }
        return Optional.of(worst);
    }

    /** The lowest ceiling forecast anywhere in the validity period. */
    public OptionalInt lowestForecastCeilingFt() {
        return groups.stream()
                .map(TafGroup::conditions)
                .map(SurfaceConditions::ceilingFt)
                .filter(OptionalInt::isPresent)
                .mapToInt(OptionalInt::getAsInt)
                .min();
    }

    /** The change groups active at an instant, which is what an OFP weather page lists. */
    public List<TafGroup> groupsActiveAt(Instant when) {
        return groups.stream().filter(g -> g.appliesAt(when)).toList();
    }

    /**
     * Combines two forecasts into the worse of the two, element by element. Where one of them
     * leaves an element unreported the other's value stands: an unreported element in a
     * {@code TEMPO} group means "unchanged", not "unknown".
     */
    private static SurfaceConditions worse(SurfaceConditions prevailing, SurfaceConditions fluctuation) {
        Integer visibility = lowerOf(
                prevailing.visibility().isPresent() ? prevailing.visibility().getAsInt() : null,
                fluctuation.visibilityMetres());
        List<CloudLayer> clouds = fluctuation.clouds().isEmpty()
                ? prevailing.clouds()
                : lowerCeilingOf(prevailing, fluctuation);
        List<String> weather = new ArrayList<>(prevailing.presentWeather());
        fluctuation.presentWeather().stream()
                .filter(w -> !weather.contains(w))
                .forEach(weather::add);

        boolean strongerWind = fluctuation.peakWindKt() > prevailing.peakWindKt();
        return new SurfaceConditions(
                strongerWind ? fluctuation.wind() : prevailing.wind(),
                strongerWind ? fluctuation.variableWind() : prevailing.variableWind(),
                strongerWind ? fluctuation.gustKt() : prevailing.gustKt(),
                prevailing.windVariesFromDeg(),
                prevailing.windVariesToDeg(),
                visibility,
                // A fluctuation that degrades anything cannot leave CAVOK standing.
                prevailing.cavok() && fluctuation.cavok(),
                clouds,
                weather);
    }

    private static List<CloudLayer> lowerCeilingOf(SurfaceConditions a, SurfaceConditions b) {
        OptionalInt ceilingA = a.ceilingFt();
        OptionalInt ceilingB = b.ceilingFt();
        if (ceilingA.isEmpty()) {
            return b.clouds();
        }
        if (ceilingB.isEmpty()) {
            return a.clouds();
        }
        return ceilingB.getAsInt() < ceilingA.getAsInt() ? b.clouds() : a.clouds();
    }

    private static Integer lowerOf(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    @Override
    public String toString() {
        return raw;
    }
}
