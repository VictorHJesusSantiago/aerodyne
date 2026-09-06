package io.aerodyne.api.service;

import io.aerodyne.dispatch.notam.Notam;
import io.aerodyne.dispatch.notam.NotamParser;
import io.aerodyne.weather.ConstantWeatherModel;
import io.aerodyne.weather.WeatherModel;
import io.aerodyne.weather.grib.UpperAirForecast;
import io.aerodyne.weather.metar.MetarParser;
import io.aerodyne.weather.metar.MetarReport;
import io.aerodyne.weather.metar.TafParser;
import io.aerodyne.weather.metar.TafReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The weather the planning engine sees: upper winds for the fuel integration, forecasts and
 * NOTAMs for the alternates.
 *
 * <p>Everything here is held in memory and fed in from outside — a GRIB file dropped into a
 * directory, a bulletin posted to the API. That is what the data actually is: a stream of
 * observations and forecasts that expire and are replaced, with no history worth keeping and no
 * relational structure worth modelling. Putting a METAR in a table would mean writing a row
 * every minute for every aerodrome in the world in order to answer a question about the next
 * two hours.
 *
 * <p>With nothing loaded, the engine plans against the standard atmosphere with no wind. That is
 * a real answer and not a failure — it is what a plan looks like before the forecast arrives —
 * and every plan carries {@link WeatherModel#source()} so that it is visible which it got.
 *
 * <p>Thread-safe: the maps are concurrent and the model reference is swapped atomically, so a
 * plan in progress keeps the forecast it started with.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final AtomicReference<WeatherModel> upperAir = new AtomicReference<>();
    private final Map<String, TafReport> forecasts = new ConcurrentHashMap<>();
    private final Map<String, MetarReport> observations = new ConcurrentHashMap<>();
    private final List<Notam> notams = new CopyOnWriteArrayList<>();

    /**
     * The wind model to plan against.
     *
     * @param offBlocks when the flight starts, so a forecast that has expired can be spotted
     */
    public WeatherModel forDeparture(Instant offBlocks) {
        WeatherModel loaded = upperAir.get();
        if (loaded == null) {
            return ConstantWeatherModel.isa();
        }
        return loaded;
    }

    /** The upper-air forecast currently loaded, when one is. */
    public Optional<WeatherModel> upperAirForecast() {
        return Optional.ofNullable(upperAir.get());
    }

    /** Loads a GRIB2 file and makes it the forecast the engine plans against. */
    public WeatherModel loadUpperAir(Path gribFile) {
        WeatherModel model = UpperAirForecast.load(gribFile);
        upperAir.set(model);
        log.info("loaded upper-air forecast from {}, issued {}", model.source(), model.issuedAt());
        return model;
    }

    /**
     * Loads a GRIB2 forecast from bytes that arrived over the wire.
     *
     * <p>Preferred over {@link #loadUpperAir(Path)}: a path names a file on the server, which
     * means the caller chooses what the server reads. An upload carries its own content and
     * needs no such trust.
     */
    public WeatherModel loadUpperAir(byte[] data, String name) {
        WeatherModel model = UpperAirForecast.load(data, name);
        upperAir.set(model);
        log.info("loaded upper-air forecast from upload {} ({} bytes), issued {}",
                name, data.length, model.issuedAt());
        return model;
    }

    /** Accepts a TAF, replacing any earlier forecast for the same aerodrome. */
    public TafReport submitTaf(String raw) {
        TafReport forecast = new TafParser().parse(raw);
        forecasts.put(forecast.stationIcao(), forecast);
        return forecast;
    }

    /** Accepts a METAR, replacing any earlier observation for the same aerodrome. */
    public MetarReport submitMetar(String raw) {
        MetarReport observation = new MetarParser().parse(raw);
        // A bulletin can arrive out of order; an older observation must not displace a newer one.
        observations.merge(observation.stationIcao(), observation,
                (existing, arriving) -> arriving.observedAt().isAfter(existing.observedAt())
                        ? arriving
                        : existing);
        return observations.get(observation.stationIcao());
    }

    /** Accepts a NOTAM, replacing the one it supersedes when it is a replacement. */
    public Notam submitNotam(String raw) {
        Notam notam = new NotamParser().parse(raw);
        if (notam.replacesOrCancels() != null) {
            notams.removeIf(existing ->
                    existing.identifier().equals(notam.replacesOrCancels()));
        }
        notams.removeIf(existing -> existing.identifier().equals(notam.identifier()));
        if (notam.type() != Notam.NotamType.CANCELLATION) {
            notams.add(notam);
        }
        return notam;
    }

    /** The forecasts held, by ICAO code. */
    public Map<String, TafReport> forecasts() {
        return Map.copyOf(forecasts);
    }

    /** The observations held, by ICAO code. */
    public Map<String, MetarReport> observations() {
        return Map.copyOf(observations);
    }

    /** The NOTAMs held. */
    public List<Notam> notams() {
        return List.copyOf(notams);
    }

    public Optional<TafReport> forecastFor(String icaoCode) {
        return Optional.ofNullable(forecasts.get(icaoCode));
    }

    public Optional<MetarReport> observationFor(String icaoCode) {
        return Optional.ofNullable(observations.get(icaoCode));
    }

    /** Drops observations that are too old to dispatch against and NOTAMs that have expired. */
    public int prune(Instant now) {
        int before = observations.size() + notams.size();
        observations.values().removeIf(observation -> !observation.isCurrentAt(now));
        notams.removeIf(notam -> !notam.isActiveAt(now) && !notam.isPermanent());
        return before - (observations.size() + notams.size());
    }
}
