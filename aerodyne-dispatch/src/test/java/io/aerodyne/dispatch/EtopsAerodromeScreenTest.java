package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Runway;
import io.aerodyne.dispatch.notam.Notam;
import io.aerodyne.dispatch.notam.NotamParser;
import io.aerodyne.weather.metar.TafParser;
import io.aerodyne.weather.metar.TafReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Screening the aerodromes an ETOPS diversion could be made to.
 *
 * <p>A North Atlantic crossing with the three aerodromes that actually matter on it. What is
 * being pinned down is the difference between an aerodrome that is <em>adequate</em> — long
 * enough runway, not closed — and one that is <em>suitable</em>, meaning also forecast to be
 * landable at the time a diversion would arrive. A route assessed against merely adequate
 * aerodromes understates its diversion times, so the screen has to say which of the two it did.
 */
class EtopsAerodromeScreenTest {

    private static final Instant OFF_BLOCKS = Instant.parse("2024-06-15T12:00:00Z");
    private static final double GROUND_SPEED_KT = 450.0;
    private static final int APPROVAL_MINUTES = 180;

    /** Dublin to Boston, the way the ETOPS tests elsewhere fly it. */
    private static final RoutePath PATH = new RoutePath(List.of(
            GeoPoint.of(53.421389, -6.270000),
            GeoPoint.of(55.0, -20.0),
            GeoPoint.of(53.0, -40.0),
            GeoPoint.of(42.362944, -71.006389)));

    private final NavigationDatabase database = NavigationDatabase.builder()
            .cycle(new AiracCycle(2024, 13))
            .airports(List.of(
                    airport("EINN", 52.702, -8.925, 10_500),
                    airport("BIKF", 63.985, -22.605, 10_000),
                    airport("CYQX", 48.937, -54.568, 10_200),
                    // Too short for a diversion, whatever the weather does.
                    airport("EIWF", 52.187, -7.087, 4_700)))
            .build();

    private final TafParser tafs = new TafParser(Instant.parse("2024-06-15T11:00:00Z"));
    private final EtopsAerodromeScreen screen = new EtopsAerodromeScreen(database);

    private static Airport airport(String icao, double latitude, double longitude, int runwayFt) {
        Runway runway = new Runway(icao, "RW09", GeoPoint.of(latitude, longitude), 90.0,
                runwayFt, 150, 100, 0, 0.0, "I" + icao.substring(1));
        return new Airport(icao, null, GeoPoint.of(latitude, longitude), 100, 6000,
                runwayFt, "C", icao, List.of(runway));
    }

    private TafReport good(String icao) {
        return tafs.parse("TAF " + icao + " 151100Z 1512/1618 25012KT CAVOK");
    }

    private TafReport belowMinima(String icao) {
        return tafs.parse("TAF " + icao + " 151100Z 1512/1618 25012KT 1200 BR BKN003");
    }

    private Map<String, TafReport> forecasts(Map<String, TafReport> entries) {
        return new LinkedHashMap<>(entries);
    }

    private EtopsAerodromeScreen.Result screenWith(Map<String, TafReport> forecasts,
                                                   List<Notam> notams) {
        return screen.along(PATH, AlternateCriteria.forJetWithPrecisionApproach()
                        .withMinimumRunwayFt(7_000),
                forecasts, notams, OFF_BLOCKS, GROUND_SPEED_KT, APPROVAL_MINUTES);
    }

    @Nested
    @DisplayName("with forecasts in hand")
    class Screened {

        @Test
        void an_aerodrome_forecast_usable_is_counted() {
            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", good("EINN"), "BIKF", good("BIKF"), "CYQX", good("CYQX"))), List.of());

            assertThat(result.weatherScreened()).isTrue();
            assertThat(result.adequate()).extracting(Airport::icaoCode)
                    .contains("EINN", "CYQX");
        }

        @Test
        void an_aerodrome_forecast_below_minima_is_refused_with_its_reason() {
            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", belowMinima("EINN"), "BIKF", good("BIKF"),
                    "CYQX", good("CYQX"))), List.of());

            assertThat(result.adequate()).extracting(Airport::icaoCode).doesNotContain("EINN");
            assertThat(result.reasons()).anySatisfy(reason ->
                    assertThat(reason).startsWith("EINN:"));
        }

        @Test
        void a_short_runway_rules_an_aerodrome_out_whatever_the_forecast_says() {
            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", good("EINN"), "EIWF", good("EIWF"),
                    "CYQX", good("CYQX"))), List.of());

            assertThat(result.adequate()).extracting(Airport::icaoCode).doesNotContain("EIWF");
            assertThat(result.reasons()).anySatisfy(reason ->
                    assertThat(reason).contains("EIWF").contains("runway"));
        }

        @Test
        void an_aerodrome_closed_by_notam_is_refused() {
            Notam closed = new NotamParser().parse(
                    "A0123/24 NOTAMN\n"
                            + "Q) EISN/QFALC/IV/NBO/A/000/999/5270N00855W005\n"
                            + "A) EINN B) 2406150000 C) 2406162359\n"
                            + "E) AERODROME CLOSED");

            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", good("EINN"), "CYQX", good("CYQX"))), List.of(closed));

            assertThat(result.adequate()).extracting(Airport::icaoCode).doesNotContain("EINN");
        }
    }

    @Nested
    @DisplayName("with no forecast to screen against")
    class Unscreened {

        @Test
        void it_falls_back_to_runway_length_rather_than_declaring_the_route_unassessable() {
            EtopsAerodromeScreen.Result result = screenWith(Map.of(), List.of());

            assertThat(result.isEmpty()).isFalse();
            assertThat(result.adequate()).extracting(Airport::icaoCode)
                    .contains("EINN", "CYQX")
                    .doesNotContain("EIWF");
        }

        @Test
        void and_it_says_so_rather_than_letting_the_caller_assume_otherwise() {
            assertThat(screenWith(Map.of(), List.of()).weatherScreened()).isFalse();
        }

        @Test
        void the_same_is_true_when_every_forecast_is_below_minima() {
            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", belowMinima("EINN"), "BIKF", belowMinima("BIKF"),
                    "CYQX", belowMinima("CYQX"))), List.of());

            assertThat(result.weatherScreened()).isFalse();
            assertThat(result.adequate()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("what the assessment does with it")
    class Assessment {

        private EtopsAnalysis analyse(boolean weatherScreened) {
            EtopsAerodromeScreen.Result result = screenWith(forecasts(Map.of(
                    "EINN", good("EINN"), "BIKF", good("BIKF"),
                    "CYQX", good("CYQX"))), List.of());
            return new EtopsAnalyser(new DiversionCalculator(
                    io.aerodyne.weather.ConstantWeatherModel.isa(), 340.0, 22_000.0))
                    .analyse(PATH, result.adequate(), APPROVAL_MINUTES, OFF_BLOCKS,
                            GROUND_SPEED_KT, weatherScreened);
        }

        @Test
        void a_screened_assessment_says_nothing_extra() {
            assertThat(analyse(true).weatherScreened()).isTrue();
            assertThat(analyse(true).summary()).doesNotContain("runway length only");
        }

        @Test
        void an_unscreened_one_says_so_on_the_ofp() {
            EtopsAnalysis unscreened = analyse(false);

            assertThat(unscreened.weatherScreened()).isFalse();
            assertThat(unscreened.summary()).contains("runway length only");
        }

        @Test
        void the_default_for_an_assessment_that_was_not_told_is_unscreened() {
            EtopsAnalysis untold = new EtopsAnalysis(180, 60,
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    GeoPoint.of(53.0, -30.0), 120.0, "BIKF", List.of("BIKF"), List.of());

            assertThat(untold.weatherScreened()).isFalse();
        }
    }
}
