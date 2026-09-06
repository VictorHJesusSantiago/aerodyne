package io.aerodyne.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads an aircraft type's performance from a file.
 *
 * <p>{@link AircraftTypes} holds two worked examples, and says plainly that their numbers are
 * constructed rather than taken from a flight manual. This is how an operation replaces them.
 * Performance data is the one part of a planning engine that cannot be written by whoever writes
 * the engine: it is measured by the manufacturer, revised by service bulletin, and specific to
 * an airframe's configuration. So the engine's job is to define the shape the data goes in, and
 * this is that shape.
 *
 * <p>The format is JSON, laid out the way the published tables are: an axis of weights, an axis
 * of altitudes, and a grid of values read against the two. That correspondence is deliberate —
 * transcribing a printed table should be a matter of copying rows, not of rearranging them —
 * and it is why the file repeats the axes for each table rather than sharing one set.
 *
 * <p>Validation is strict. A table with the wrong number of rows, an axis that does not ascend,
 * a missing envelope: all refused at load, by name, rather than accepted and discovered later as
 * a fuel figure nobody can explain.
 *
 * <pre>{@code
 * {
 *   "typeCode": "B738",
 *   "name": "Boeing 737-800",
 *   "engineCount": 2,
 *   "weights": { "operatingEmptyKg": 41500, ... },
 *   "cruise": {
 *     "weightsKg": [55000, 79000],
 *     "altitudesFt": [25000, 31000, 35000],
 *     "mach":     [[0.780, 0.785, 0.785], [0.780, 0.782, 0.780]],
 *     "fuelFlowKgPerHour": [[2900, 2600, 2400], [3500, 3150, 2950]],
 *     "isaFuelFactorPerDegreeC": 0.004,
 *     "optimumAltitude": { "weightsKg": [55000, 79000], "altitudeFt": [39500, 33500] }
 *   },
 *   "climb":   { "weightsKg": [...], "altitudesFt": [...], "minutes": [[...]], ... },
 *   "descent": { ... },
 *   "geometry": { "macLengthMetres": 4.17, ... },
 *   "cgEnvelope": [ { "weightKg": 41000, "cgPercentMac": 10.0 }, ... ],
 *   "engineOut": { "diversionSpeedKt": 380, ... },
 *   "etopsApprovalMinutes": 120,
 *   "provenance": "MANUFACTURER"
 * }
 * }</pre>
 */
public final class AircraftTypeLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Loads a type from a file. */
    public AircraftType load(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader, file.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read aircraft performance file " + file, e);
        }
    }

    /** Loads a type from a stream, naming the source so that errors can say where they were. */
    public AircraftType load(InputStream stream, String source) {
        try {
            return build(mapper.readTree(stream), source);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read aircraft performance from " + source, e);
        }
    }

    public AircraftType load(Reader reader, String source) {
        try {
            return build(mapper.readTree(reader), source);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read aircraft performance from " + source, e);
        }
    }

    /** Loads every {@code .json} file in a directory, which is how a fleet is configured. */
    public List<AircraftType> loadDirectory(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    private AircraftType build(JsonNode root, String source) {
        String typeCode = requiredText(root, "typeCode", source);

        WeightLimits weights = weights(required(root, "weights", source), source);
        CruisePerformance cruise = cruise(required(root, "cruise", source), typeCode, source);
        VerticalPerformance climb = vertical(required(root, "climb", source),
                typeCode + " climb", source);
        VerticalPerformance descent = vertical(required(root, "descent", source),
                typeCode + " descent", source);
        CentreOfGravityEnvelope envelope = envelope(required(root, "cgEnvelope", source),
                typeCode, source);

        JsonNode geometry = required(root, "geometry", source);
        JsonNode engineOut = required(root, "engineOut", source);

        try {
            return new AircraftType(
                    typeCode,
                    root.path("name").asText(typeCode),
                    wakeCategory(root, weights),
                    root.path("engineCount").asInt(2),
                    weights, cruise, climb, descent, envelope,
                    requiredNumber(geometry, "macLengthMetres", source),
                    requiredNumber(geometry, "macLeadingEdgeArmMetres", source),
                    requiredNumber(geometry, "operatingEmptyArmMetres", source),
                    requiredNumber(engineOut, "diversionSpeedKt", source),
                    requiredNumber(engineOut, "diversionFuelFlowKgPerHour", source),
                    requiredNumber(engineOut, "singleEngineCeilingFt", source),
                    root.hasNonNull("etopsApprovalMinutes")
                            ? root.get("etopsApprovalMinutes").asInt()
                            : null,
                    provenance(root, source));
        } catch (IllegalArgumentException invalid) {
            // The domain records enforce their own invariants; say which file broke one.
            throw new InvalidPerformanceDataException(source, invalid.getMessage());
        }
    }

    private static WeightLimits weights(JsonNode node, String source) {
        try {
            return new WeightLimits(
                    requiredNumber(node, "operatingEmptyKg", source),
                    requiredNumber(node, "maxZeroFuelKg", source),
                    requiredNumber(node, "maxTakeoffKg", source),
                    requiredNumber(node, "maxLandingKg", source),
                    requiredNumber(node, "maxFuelKg", source));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidPerformanceDataException(source, "weights: " + invalid.getMessage());
        }
    }

    private static CruisePerformance cruise(JsonNode node, String typeCode, String source) {
        double[] weights = axis(node, "weightsKg", source);
        double[] altitudes = axis(node, "altitudesFt", source);

        JsonNode optimum = required(node, "optimumAltitude", source);
        double[] optimumWeights = axis(optimum, "weightsKg", source);
        double[] optimumValues = axis(optimum, "altitudeFt", source);
        if (optimumWeights.length != optimumValues.length) {
            throw new InvalidPerformanceDataException(source,
                    "the optimum altitude table has " + optimumWeights.length + " weights and "
                            + optimumValues.length + " altitudes");
        }
        // The optimum altitude varies with weight alone, so it is a table of one column.
        double[][] optimumGrid = new double[optimumValues.length][1];
        for (int i = 0; i < optimumValues.length; i++) {
            optimumGrid[i][0] = optimumValues[i];
        }

        return new CruisePerformance(
                table(typeCode + " cruise Mach", weights, altitudes,
                        grid(node, "mach", weights.length, altitudes.length, source), source),
                table(typeCode + " fuel flow kg/h", weights, altitudes,
                        grid(node, "fuelFlowKgPerHour", weights.length, altitudes.length, source),
                        source),
                node.path("isaFuelFactorPerDegreeC").asDouble(0.004),
                table(typeCode + " optimum altitude", optimumWeights, new double[]{0},
                        optimumGrid, source));
    }

    private static VerticalPerformance vertical(JsonNode node, String name, String source) {
        double[] weights = axis(node, "weightsKg", source);
        double[] altitudes = axis(node, "altitudesFt", source);
        return new VerticalPerformance(
                table(name + " minutes", weights, altitudes,
                        grid(node, "minutes", weights.length, altitudes.length, source), source),
                table(name + " fuel kg", weights, altitudes,
                        grid(node, "fuelKg", weights.length, altitudes.length, source), source),
                table(name + " distance nm", weights, altitudes,
                        grid(node, "distanceNm", weights.length, altitudes.length, source), source));
    }

    private static CentreOfGravityEnvelope envelope(JsonNode node, String typeCode, String source) {
        if (!node.isArray()) {
            throw new InvalidPerformanceDataException(source, "cgEnvelope must be an array");
        }
        List<CentreOfGravityEnvelope.Vertex> vertices = new ArrayList<>(node.size());
        for (JsonNode vertex : node) {
            vertices.add(new CentreOfGravityEnvelope.Vertex(
                    requiredNumber(vertex, "weightKg", source),
                    requiredNumber(vertex, "cgPercentMac", source)));
        }
        try {
            return new CentreOfGravityEnvelope(typeCode, vertices);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidPerformanceDataException(source, "cgEnvelope: " + invalid.getMessage());
        }
    }

    /**
     * Where the figures came from.
     *
     * <p>A file an operator has installed is presumed to hold their real tables, so the default
     * is {@link DataProvenance#MANUFACTURER}. A file that is a sample or a test fixture says
     * {@code "provenance": "REPRESENTATIVE"} and plans built on it are refused.
     */
    private static DataProvenance provenance(JsonNode root, String source) {
        String named = root.path("provenance").asText("");
        if (named.isBlank()) {
            return DataProvenance.MANUFACTURER;
        }
        try {
            return DataProvenance.valueOf(named.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidPerformanceDataException(source,
                    "'" + named + "' is not a provenance; use MANUFACTURER or REPRESENTATIVE");
        }
    }

    /**
     * The wake category, taken from the file when it names one and derived from the maximum
     * takeoff weight when it does not. Deriving it is right for every type but the A380, which
     * has its own category by name rather than by weight — so a file may say so.
     */
    private static WakeCategory wakeCategory(JsonNode root, WeightLimits weights) {
        String named = root.path("wakeCategory").asText("");
        if (!named.isBlank()) {
            return WakeCategory.valueOf(named.toUpperCase(java.util.Locale.ROOT));
        }
        return WakeCategory.forMaximumTakeoffWeightKg(weights.maxTakeoffKg());
    }

    // ------------------------------------------------------------------ helpers

    private static PerformanceTable table(String name, double[] weights, double[] altitudes,
                                          double[][] values, String source) {
        try {
            return new PerformanceTable(name, weights, altitudes, values);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidPerformanceDataException(source, invalid.getMessage());
        }
    }

    private static double[] axis(JsonNode node, String field, String source) {
        JsonNode axis = required(node, field, source);
        if (!axis.isArray() || axis.isEmpty()) {
            throw new InvalidPerformanceDataException(source, field + " must be a non-empty array");
        }
        double[] values = new double[axis.size()];
        for (int i = 0; i < axis.size(); i++) {
            values[i] = axis.get(i).asDouble();
        }
        return values;
    }

    private static double[][] grid(JsonNode node, String field, int rows, int columns, String source) {
        JsonNode grid = required(node, field, source);
        if (!grid.isArray() || grid.size() != rows) {
            throw new InvalidPerformanceDataException(source,
                    field + " must have " + rows + " rows, one per weight, but has "
                            + (grid.isArray() ? grid.size() : "no array at all"));
        }
        double[][] values = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            JsonNode line = grid.get(row);
            if (!line.isArray() || line.size() != columns) {
                throw new InvalidPerformanceDataException(source,
                        field + " row " + row + " must have " + columns
                                + " values, one per altitude, but has "
                                + (line.isArray() ? line.size() : "no array at all"));
            }
            for (int column = 0; column < columns; column++) {
                values[row][column] = line.get(column).asDouble();
            }
        }
        return values;
    }

    private static JsonNode required(JsonNode parent, String field, String source) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw new InvalidPerformanceDataException(source, "'" + field + "' is required");
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field, String source) {
        String value = required(parent, field, source).asText("");
        if (value.isBlank()) {
            throw new InvalidPerformanceDataException(source, "'" + field + "' is required");
        }
        return value;
    }

    private static double requiredNumber(JsonNode parent, String field, String source) {
        JsonNode node = required(parent, field, source);
        if (!node.isNumber()) {
            throw new InvalidPerformanceDataException(source,
                    "'" + field + "' must be a number, not " + node.getNodeType());
        }
        return node.asDouble();
    }

    /** Raised when a performance file cannot be turned into a usable aircraft type. */
    public static class InvalidPerformanceDataException extends RuntimeException {

        private final String source;

        public InvalidPerformanceDataException(String source, String problem) {
            super(source + ": " + problem);
            this.source = source;
        }

        /** Which file the problem was in. */
        public String source() {
            return source;
        }
    }
}
