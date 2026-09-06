package io.aerodyne.weather.metar;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A decoded METAR or SPECI: what the weather at an aerodrome actually is.
 *
 * @param stationIcao   the four-letter location indicator
 * @param observedAt    the observation time
 * @param conditions    the wind, visibility, cloud and weather
 * @param temperatureC  air temperature; {@code null} when not reported
 * @param dewPointC     dew point; {@code null} when not reported
 * @param qnhHpa        altimeter setting in hectopascals; an American {@code Annnn} group is
 *                      converted, so this is always hPa whichever form was reported
 * @param speci         true when the report was issued as a SPECI rather than on the hour
 * @param automatic     the {@code AUTO} flag: no human augmented the observation
 * @param corrected     the {@code COR} flag
 * @param runwayVisualRange the {@code Rnn/nnnn} groups, undecoded beyond their runway and value
 * @param trend         the trend group, e.g. {@code NOSIG}; {@code null} when none was given
 * @param remarks       everything after {@code RMK}, kept verbatim
 * @param raw           the report as received
 */
public record MetarReport(String stationIcao,
                          Instant observedAt,
                          SurfaceConditions conditions,
                          Integer temperatureC,
                          Integer dewPointC,
                          Double qnhHpa,
                          boolean speci,
                          boolean automatic,
                          boolean corrected,
                          List<RunwayVisualRange> runwayVisualRange,
                          String trend,
                          String remarks,
                          String raw) {

    /** How old an observation may be before dispatch stops treating it as current. */
    public static final Duration VALIDITY = Duration.ofMinutes(90);

    public MetarReport {
        runwayVisualRange = runwayVisualRange == null ? List.of() : List.copyOf(runwayVisualRange);
    }

    /** One {@code R27L/0800V1200U} group. */
    public record RunwayVisualRange(String runway, int minimumMetres, Integer maximumMetres,
                                    String tendency) {

        /** The value to plan against: the lower end when a range was reported. */
        public int planningValueMetres() {
            return minimumMetres;
        }
    }

    public Optional<Double> qnh() {
        return Optional.ofNullable(qnhHpa);
    }

    public OptionalDouble temperature() {
        return temperatureC == null ? OptionalDouble.empty() : OptionalDouble.of(temperatureC);
    }

    /**
     * The spread between temperature and dew point. A small spread is what turns a clear night
     * into fog, so it is what an alternate held for a marginal aerodrome is judged on.
     */
    public OptionalDouble dewPointSpreadC() {
        if (temperatureC == null || dewPointC == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(temperatureC - dewPointC);
    }

    /** True when the observation is recent enough to dispatch against at {@code now}. */
    public boolean isCurrentAt(Instant now) {
        return !observedAt.isAfter(now) && Duration.between(observedAt, now).compareTo(VALIDITY) <= 0;
    }

    /** The RVR for a runway, when the report carries one. */
    public Optional<RunwayVisualRange> rvrFor(String runway) {
        String wanted = runway.startsWith("R") ? runway.substring(1) : runway;
        return runwayVisualRange.stream()
                .filter(r -> r.runway().equalsIgnoreCase(wanted))
                .findFirst();
    }

    @Override
    public String toString() {
        return raw;
    }
}
