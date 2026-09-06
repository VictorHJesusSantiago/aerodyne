package io.aerodyne.weather.metar;

import io.aerodyne.weather.WindVector;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The weather at an aerodrome, however it was reported.
 *
 * <p>A METAR observation and a TAF forecast group say the same kinds of thing, and dispatch
 * asks the same questions of both — is there a ceiling, how far can you see, is it forecast to
 * be below minima. So both decode into this.
 *
 * @param wind             the surface wind; {@link WindVector#CALM} for {@code 00000KT}
 * @param variableWind     true for {@code VRB}, where the direction in {@code wind} is not
 *                         meaningful and only the speed is
 * @param gustKt           the gust speed, when one was reported
 * @param windVariesFromDeg the lower end of a reported variable direction range
 * @param windVariesToDeg  the upper end of that range
 * @param visibilityMetres prevailing visibility; {@code null} when not reported
 * @param cavok            {@code CAVOK} was reported: at least 10 km, no cloud below 5000 ft or
 *                         the highest minimum sector altitude, no cumulonimbus, no significant weather
 * @param clouds           the reported layers, lowest first
 * @param presentWeather   the raw present-weather groups, e.g. {@code -RA}, {@code +TSRA}
 */
public record SurfaceConditions(WindVector wind,
                                boolean variableWind,
                                Integer gustKt,
                                Integer windVariesFromDeg,
                                Integer windVariesToDeg,
                                Integer visibilityMetres,
                                boolean cavok,
                                List<CloudLayer> clouds,
                                List<String> presentWeather) {

    /** The visibility CAVOK asserts, and the value used when comparing it against a minimum. */
    public static final int CAVOK_VISIBILITY_METRES = 10_000;
    /** The height below which CAVOK asserts there is no cloud. */
    public static final int CAVOK_CEILING_FT = 5_000;

    public SurfaceConditions {
        clouds = clouds == null ? List.of() : List.copyOf(clouds);
        presentWeather = presentWeather == null ? List.of() : List.copyOf(presentWeather);
    }

    /**
     * The ceiling: the base of the lowest broken or overcast layer, in feet above the
     * aerodrome. Empty when the sky is clear or no layer reaches broken — which is a ceiling of
     * "unlimited", not a ceiling of zero, and the difference decides whether an alternate is legal.
     */
    public OptionalInt ceilingFt() {
        if (cavok) {
            return OptionalInt.empty();
        }
        return clouds.stream()
                .filter(CloudLayer::isCeiling)
                .mapToInt(CloudLayer::baseFtAgl)
                .min();
    }

    /** Prevailing visibility, with CAVOK read as the ten kilometres it asserts. */
    public OptionalInt visibility() {
        if (cavok) {
            return OptionalInt.of(CAVOK_VISIBILITY_METRES);
        }
        return visibilityMetres == null ? OptionalInt.empty() : OptionalInt.of(visibilityMetres);
    }

    public Optional<Integer> gust() {
        return Optional.ofNullable(gustKt);
    }

    /** The strongest wind to plan against: the gust when one is reported, else the mean. */
    public double peakWindKt() {
        return gustKt == null ? wind.speedKt() : gustKt;
    }

    /**
     * True when the reported conditions are at or above a ceiling and visibility minimum.
     *
     * <p>An unreported element is treated as not meeting the minimum. Dispatch cannot sign for
     * an alternate on the strength of a value that is missing, and a report that omits the
     * visibility is exactly the case where assuming the best would be worst.
     */
    public boolean meets(int requiredCeilingFt, int requiredVisibilityMetres) {
        OptionalInt visibility = visibility();
        if (visibility.isEmpty() || visibility.getAsInt() < requiredVisibilityMetres) {
            return false;
        }
        OptionalInt ceiling = ceilingFt();
        // No ceiling reported means nothing broken or worse was seen: the sky is not the limit.
        return ceiling.isEmpty() || ceiling.getAsInt() >= requiredCeilingFt;
    }

    /** True when any present-weather group reports a thunderstorm. */
    public boolean hasThunderstorm() {
        return presentWeather.stream().anyMatch(w -> w.contains("TS"))
                || clouds.stream().anyMatch(c -> "CB".equals(c.convective()));
    }

    /** True when freezing precipitation or freezing fog was reported. */
    public boolean hasFreezingPrecipitation() {
        return presentWeather.stream().anyMatch(w -> w.contains("FZ"));
    }

    /** True when the report carries any of the codes that ground or delay an operation. */
    public boolean hasSignificantWeather() {
        return !presentWeather.isEmpty() && !cavok;
    }

    /** The lowest cloud layer of any amount, which is what a visual approach is judged on. */
    public Optional<CloudLayer> lowestLayer() {
        return clouds.stream()
                .filter(c -> c.baseFtAgl() != null)
                .min(java.util.Comparator.comparingInt(CloudLayer::baseFtAgl));
    }
}
