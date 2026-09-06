package io.aerodyne.api.web;

import io.aerodyne.api.service.WeatherService;
import io.aerodyne.dispatch.notam.Notam;
import io.aerodyne.weather.metar.MetarReport;
import io.aerodyne.weather.metar.TafReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * Feeding weather in, and reading back what the engine has decoded from it.
 *
 * <p>Reports are submitted as the text they arrive as, not as a structured body. That is the
 * form they exist in — a METAR comes off a bulletin feed as a line of text — and re-encoding one
 * into JSON before submitting it would mean the caller doing the decoding this service exists to
 * do, and doing it differently.
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weather;

    public WeatherController(WeatherService weather) {
        this.weather = weather;
    }

    /** What weather the engine currently holds. */
    @GetMapping
    public WeatherStatus status() {
        return new WeatherStatus(
                weather.upperAirForecast().map(model -> model.source()).orElse(null),
                weather.upperAirForecast().map(model -> model.issuedAt()).orElse(null),
                weather.observations().size(),
                weather.forecasts().size(),
                weather.notams().size());
    }

    /** Submits a METAR or SPECI. */
    @PostMapping(value = "/metar", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ObservationSummary metar(@RequestBody String raw) {
        MetarReport report = weather.submitMetar(raw);
        return ObservationSummary.of(report);
    }

    /** Submits a TAF. */
    @PostMapping(value = "/taf", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ForecastSummary taf(@RequestBody String raw) {
        TafReport forecast = weather.submitTaf(raw);
        return new ForecastSummary(forecast.stationIcao(), forecast.validFrom(),
                forecast.validTo(), forecast.groups().size(), forecast.cancelled(),
                toInteger(forecast.lowestForecastCeilingFt()));
    }

    /** Submits a NOTAM. */
    @PostMapping(value = "/notam", consumes = MediaType.TEXT_PLAIN_VALUE)
    public NotamSummary notam(@RequestBody String raw) {
        Notam notam = weather.submitNotam(raw);
        return new NotamSummary(notam.identifier(), notam.type().name(), notam.locations(),
                notam.qCode().toString(), notam.qCode().describe(),
                notam.validFrom(), notam.validTo(), notam.isOperationallySignificant());
    }

    /**
     * Loads a GRIB2 upper-air forecast from an uploaded file.
     *
     * <p>This is the way to load one. The server reads only what was sent to it, so nothing
     * about the request decides which of the server's own files get opened.
     */
    @PostMapping(value = "/upper-air", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public WeatherStatus upperAirUpload(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile upload)
            throws java.io.IOException {
        if (upload.isEmpty()) {
            throw new IllegalArgumentException("the uploaded forecast is empty");
        }
        String name = upload.getOriginalFilename() == null ? "upload" : upload.getOriginalFilename();
        weather.loadUpperAir(upload.getBytes(), name);
        return status();
    }

    /**
     * Loads a GRIB2 forecast from a path on the server.
     *
     * <p>Kept for an installation whose forecasts are dropped into a directory by a feed rather
     * than pushed over the API. It reads whatever path it is given, so it is only safe behind
     * the authentication this repository does not yet have; the upload form above is the one to
     * reach for otherwise.
     */
    @PostMapping("/upper-air/from-path")
    public WeatherStatus upperAir(@RequestParam String file) {
        weather.loadUpperAir(Path.of(file));
        return status();
    }

    /** The current observation for an aerodrome, if one has been submitted. */
    @GetMapping("/metar/{icao}")
    public ObservationSummary metarFor(@PathVariable String icao) {
        return weather.observationFor(icao)
                .map(ObservationSummary::of)
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("observation", icao));
    }

    /** The conditions a forecast gives at a moment, prevailing and worst case. */
    @GetMapping("/taf/{icao}")
    public ForecastAt tafFor(@PathVariable String icao,
                             @RequestParam(required = false) Instant at) {
        TafReport forecast = weather.forecastFor(icao)
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("forecast", icao));
        Instant when = at == null ? Instant.now() : at;

        return new ForecastAt(icao, when,
                forecast.prevailingAt(when).map(c -> toInteger(c.ceilingFt())).orElse(null),
                forecast.prevailingAt(when).map(c -> toInteger(c.visibility())).orElse(null),
                forecast.worstCaseAt(when).map(c -> toInteger(c.ceilingFt())).orElse(null),
                forecast.worstCaseAt(when).map(c -> toInteger(c.visibility())).orElse(null),
                forecast.covers(when));
    }

    /** Drops what has expired, and says how much went. */
    @PostMapping("/prune")
    public PruneResult prune() {
        return new PruneResult(weather.prune(Instant.now()));
    }

    /** What the engine holds. */
    public record WeatherStatus(String upperAirSource, Instant upperAirIssuedAt,
                                int observations, int forecasts, int notams) {
    }

    /** A decoded observation. */
    public record ObservationSummary(String station, Instant observedAt, String wind,
                                     Integer visibilityMetres, Integer ceilingFt,
                                     Integer temperatureC, Double qnhHpa, boolean cavok) {

        static ObservationSummary of(MetarReport report) {
            return new ObservationSummary(report.stationIcao(), report.observedAt(),
                    report.conditions().wind().toString(),
                    toInteger(report.conditions().visibility()),
                    toInteger(report.conditions().ceilingFt()),
                    report.temperatureC(),
                    report.qnh().orElse(null),
                    report.conditions().cavok());
        }
    }

    /** A decoded forecast. */
    public record ForecastSummary(String station, Instant validFrom, Instant validTo,
                                  int groups, boolean cancelled, Integer lowestCeilingFt) {
    }

    /** What a forecast gives at one moment. */
    public record ForecastAt(String station, Instant at,
                             Integer prevailingCeilingFt, Integer prevailingVisibilityMetres,
                             Integer worstCaseCeilingFt, Integer worstCaseVisibilityMetres,
                             boolean covered) {
    }

    /** A decoded NOTAM. */
    public record NotamSummary(String identifier, String type, List<String> locations,
                               String qCode, String describes, Instant validFrom,
                               Instant validTo, boolean operationallySignificant) {
    }

    /** How much was dropped. */
    public record PruneResult(int dropped) {
    }

    /** An absent {@code OptionalInt} becomes {@code null} in JSON rather than a wrapper object. */
    private static Integer toInteger(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }
}
