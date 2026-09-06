package io.aerodyne.api.service;

import io.aerodyne.performance.AircraftType;
import io.aerodyne.performance.AircraftTypeLoader;
import io.aerodyne.performance.AircraftTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The aircraft types this installation can plan for.
 *
 * <p>Types are held in memory rather than in the database, and deliberately: a type's
 * performance is a set of tables that changes when the manufacturer revises them, not per
 * flight. Putting them in a table would invite editing them through the API, which is precisely
 * the thing that must not happen to certified performance data.
 *
 * <p>Where they come from is configuration. Set {@code aerodyne.performance.directory} to a
 * directory of JSON files and the fleet is whatever is in it — see {@link AircraftTypeLoader}
 * for the format. With nothing configured, the two worked examples from the performance module
 * are registered instead, and those are representative figures rather than manufacturer data,
 * which {@link AircraftTypes} says plainly. An installation planning real flights supplies its
 * own; {@link #usingBuiltInTypes()} says which it got.
 */
@Component
public class AircraftTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(AircraftTypeRegistry.class);

    private final Map<String, AircraftType> types = new LinkedHashMap<>();
    private final boolean builtIn;

    /** For tests and for callers wiring the registry by hand: the built-in example types. */
    public AircraftTypeRegistry() {
        this(null);
    }

    /**
     * Marked as the one to inject. With two constructors and neither annotated, Spring picks
     * the no-argument one and the configured directory is ignored without a word — which would
     * mean planning real flights on the example tables and never being told.
     */
    @Autowired
    public AircraftTypeRegistry(
            @Value("${aerodyne.performance.directory:}") String performanceDirectory) {
        List<AircraftType> loaded = loadFrom(performanceDirectory);
        if (loaded.isEmpty()) {
            log.warn("no performance directory configured; planning with the built-in example "
                    + "types, whose tables are representative rather than manufacturer data");
            register(AircraftTypes.narrowbodyTwin());
            register(AircraftTypes.widebodyTwin());
            this.builtIn = true;
        } else {
            loaded.forEach(this::register);
            log.info("loaded {} aircraft types from {}: {}",
                    loaded.size(), performanceDirectory, codes());
            this.builtIn = false;
        }
    }

    /**
     * Reads a directory of performance files, or nothing when none is configured.
     *
     * <p>A configured directory that cannot be read is a deployment mistake, and falling back to
     * the example types would hide it behind plans that look ordinary and are built on the wrong
     * aeroplane. So it fails.
     */
    private static List<AircraftType> loadFrom(String directory) {
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        Path path = Path.of(directory);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    "aerodyne.performance.directory is set to '" + directory
                            + "', which is not a directory");
        }
        List<AircraftType> loaded = new AircraftTypeLoader().loadDirectory(path);
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "aerodyne.performance.directory '" + directory + "' holds no .json files");
        }
        return loaded;
    }

    /**
     * True when the registry is serving the built-in example types rather than data an operator
     * supplied. A plan built on these is a demonstration, not a dispatch.
     */
    public boolean usingBuiltInTypes() {
        return builtIn;
    }

    /** Adds a type, replacing any with the same designator. */
    public final void register(AircraftType type) {
        types.put(type.typeCode().toUpperCase(Locale.ROOT), type);
    }

    public Optional<AircraftType> find(String typeCode) {
        return typeCode == null
                ? Optional.empty()
                : Optional.ofNullable(types.get(typeCode.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * The type, or a refusal naming what is available. A plan for a type the installation does
     * not hold tables for cannot be computed at all, and saying which types it does hold is more
     * use than saying the request was bad.
     */
    public AircraftType require(String typeCode) {
        return find(typeCode).orElseThrow(() -> new IllegalArgumentException(
                "no performance data for aircraft type '" + typeCode
                        + "'; this installation holds " + String.join(", ", codes())));
    }

    public Set<String> codes() {
        return Set.copyOf(types.keySet());
    }
}
