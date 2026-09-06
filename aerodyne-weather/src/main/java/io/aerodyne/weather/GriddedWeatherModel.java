package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Units;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A forecast made of isobaric levels, interpolated in three dimensions and in time.
 *
 * <p>Horizontally each level interpolates bilinearly within its own grid. Vertically the model
 * interpolates <em>on pressure altitude</em> rather than on pressure: a cruise sits between two
 * levels that are equally spaced in pressure but not in height, and it is the height spacing
 * that matches how the wind actually shears. Above the top level and below the bottom one the
 * nearest level is used unchanged, because extrapolating a jet stream upwards invents a wind.
 *
 * <p>In time, a plan crossing a forecast boundary interpolates between the two valid times that
 * bracket it. Beyond the last valid time the model reports it no longer {@link #covers} the
 * flight, and still answers with the closest forecast it has — a plan that silently used a
 * twelve-hour-old wind would be worse than one that says which wind it used.
 */
public final class GriddedWeatherModel implements WeatherModel {

    /** How far past the last valid time the forecast is still considered to cover a flight. */
    private static final Duration VALIDITY_MARGIN = Duration.ofHours(3);

    private final List<ForecastHour> hours;
    private final Instant issuedAt;
    private final String source;
    private final double strongestWindKt;

    private GriddedWeatherModel(List<ForecastHour> hours, Instant issuedAt, String source) {
        this.hours = hours;
        this.issuedAt = issuedAt;
        this.source = source;
        this.strongestWindKt = measureStrongestWind(hours);
    }

    /**
     * The strongest wind on any grid point of the whole forecast, walked once at construction.
     *
     * <p>A full global forecast is a few million points per level, so this is not free; it is
     * done once and kept because the alternative is a route search that either recomputes it or
     * guesses. Sampling between grid points is bilinear and a bilinear blend of a component never
     * exceeds the largest of the four around it, so the maximum over the grid points really does
     * bound everything the model can return.
     */
    private static double measureStrongestWind(List<ForecastHour> hours) {
        double strongest = 0.0;
        for (ForecastHour hour : hours) {
            for (UpperAirLevel level : hour.levels()) {
                LatLonGrid eastward = level.eastwardKt();
                LatLonGrid northward = level.northwardKt();
                for (int row = 0; row < eastward.rows(); row++) {
                    for (int column = 0; column < eastward.columns(); column++) {
                        strongest = Math.max(strongest, Math.hypot(
                                eastward.valueAt(column, row), northward.valueAt(column, row)));
                    }
                }
            }
        }
        return strongest;
    }

    /** One valid time of the forecast, with its levels sorted from the ground upwards. */
    public record ForecastHour(Instant validAt, List<UpperAirLevel> levels) {

        public ForecastHour {
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("a forecast hour needs at least one level");
            }
            List<UpperAirLevel> sorted = new ArrayList<>(levels);
            // Descending pressure is ascending altitude, which is the order the search wants.
            sorted.sort(Comparator.comparingDouble(UpperAirLevel::pressureHpa).reversed());
            levels = List.copyOf(sorted);
        }

        AtmosphericConditions at(GeoPoint position, double pressureAltitudeFt) {
            if (levels.size() == 1) {
                return levels.get(0).conditionsAt(position);
            }
            UpperAirLevel below = levels.get(0);
            UpperAirLevel above = levels.get(levels.size() - 1);
            if (pressureAltitudeFt <= below.pressureAltitudeFt()) {
                return below.conditionsAt(position);
            }
            if (pressureAltitudeFt >= above.pressureAltitudeFt()) {
                return above.conditionsAt(position);
            }
            for (int i = 0; i < levels.size() - 1; i++) {
                UpperAirLevel lower = levels.get(i);
                UpperAirLevel upper = levels.get(i + 1);
                double lowerFt = lower.pressureAltitudeFt();
                double upperFt = upper.pressureAltitudeFt();
                if (pressureAltitudeFt >= lowerFt && pressureAltitudeFt <= upperFt) {
                    double f = (pressureAltitudeFt - lowerFt) / (upperFt - lowerFt);
                    return blend(lower.conditionsAt(position), upper.conditionsAt(position), f);
                }
            }
            // Unreachable while the levels are sorted, but a wrong answer here would be silent.
            throw new IllegalStateException(
                    "no level bracket for " + pressureAltitudeFt + " ft; levels are out of order");
        }

        boolean covers(GeoPoint position) {
            return levels.get(0).covers(position);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AtmosphericConditions at(GeoPoint position, double pressureAltitudeFt, Instant validAt) {
        if (hours.size() == 1) {
            return hours.get(0).at(position, pressureAltitudeFt);
        }
        ForecastHour first = hours.get(0);
        ForecastHour last = hours.get(hours.size() - 1);
        if (!validAt.isAfter(first.validAt())) {
            return first.at(position, pressureAltitudeFt);
        }
        if (!validAt.isBefore(last.validAt())) {
            return last.at(position, pressureAltitudeFt);
        }
        for (int i = 0; i < hours.size() - 1; i++) {
            ForecastHour earlier = hours.get(i);
            ForecastHour later = hours.get(i + 1);
            if (!validAt.isBefore(earlier.validAt()) && !validAt.isAfter(later.validAt())) {
                double span = Duration.between(earlier.validAt(), later.validAt()).toSeconds();
                double into = Duration.between(earlier.validAt(), validAt).toSeconds();
                return blend(earlier.at(position, pressureAltitudeFt),
                        later.at(position, pressureAltitudeFt),
                        span == 0.0 ? 0.0 : into / span);
            }
        }
        throw new IllegalStateException("no forecast bracket for " + validAt);
    }

    @Override
    public boolean covers(GeoPoint position, Instant validAt) {
        Instant lastValid = hours.get(hours.size() - 1).validAt().plus(VALIDITY_MARGIN);
        if (validAt.isAfter(lastValid) || validAt.isBefore(hours.get(0).validAt().minus(VALIDITY_MARGIN))) {
            return false;
        }
        return hours.stream().allMatch(hour -> hour.covers(position));
    }

    /**
     * The strongest wind on any grid point of any level of any hour, measured once when the
     * model is built. Sampling between grid points is bilinear, and a bilinear interpolation of
     * a component never exceeds the largest component around it, so a maximum taken over the
     * grid points is a true bound on everything the model can return.
     */
    @Override
    public double strongestWindKt() {
        return strongestWindKt;
    }

    @Override
    public Instant issuedAt() {
        return issuedAt;
    }

    @Override
    public String source() {
        return source;
    }

    /** The valid times this forecast holds, in order. */
    public List<Instant> validTimes() {
        return hours.stream().map(ForecastHour::validAt).toList();
    }

    /** The isobaric surfaces the first forecast hour holds, in hPa, from the ground upwards. */
    public List<Double> pressureLevelsHpa() {
        return hours.get(0).levels().stream().map(UpperAirLevel::pressureHpa).toList();
    }

    private static AtmosphericConditions blend(AtmosphericConditions a,
                                               AtmosphericConditions b,
                                               double f) {
        double clamped = Math.max(0.0, Math.min(1.0, f));
        return new AtmosphericConditions(
                a.wind().interpolateTo(b.wind(), clamped),
                a.temperatureC() + (b.temperatureC() - a.temperatureC()) * clamped);
    }

    /** Assembles a model from forecast hours. */
    public static final class Builder {

        private final List<ForecastHour> hours = new ArrayList<>();
        private Instant issuedAt = Instant.EPOCH;
        private String source = "gridded forecast";

        public Builder hour(Instant validAt, List<UpperAirLevel> levels) {
            hours.add(new ForecastHour(validAt, levels));
            return this;
        }

        public Builder hour(ForecastHour hour) {
            hours.add(hour);
            return this;
        }

        public Builder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public GriddedWeatherModel build() {
            if (hours.isEmpty()) {
                throw new IllegalStateException("a gridded model needs at least one forecast hour");
            }
            hours.sort(Comparator.comparing(ForecastHour::validAt));
            return new GriddedWeatherModel(List.copyOf(hours), issuedAt, source);
        }
    }

    /** The pressure altitude, in feet, of the highest level held. */
    public double ceilingFt() {
        List<UpperAirLevel> levels = hours.get(0).levels();
        return levels.get(levels.size() - 1).pressureAltitudeFt();
    }

    /** The pressure altitude, in feet, of the lowest level held. */
    public double floorFt() {
        return hours.get(0).levels().get(0).pressureAltitudeFt();
    }

    /** A convenience for the OFP header: the band the forecast actually covers. */
    public String verticalCoverage() {
        return "FL" + Units.pressureAltitudeToFlightLevel(floorFt())
                + " to FL" + Units.pressureAltitudeToFlightLevel(ceilingFt());
    }
}
