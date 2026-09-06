package io.aerodyne.performance;

import io.aerodyne.weather.AtmosphericConditions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Loading an aircraft type from a file.
 *
 * <p>The strongest test available is that the loaded type and the built-in one agree. The
 * fixture holds the same figures as {@link AircraftTypes#narrowbodyTwin()} expressed in the file
 * format, so any mistake in the format — a transposed grid, an axis read as the wrong one, a
 * table wired to the wrong field — shows up as a difference between two things that ought to be
 * identical.
 */
class AircraftTypeLoaderTest {

    private final AircraftTypeLoader loader = new AircraftTypeLoader();

    private AircraftType loadFixture() {
        try (InputStream stream = getClass()
                .getResourceAsStream("/performance/narrowbody-twin.json")) {
            assertThat(stream).as("the fixture must be on the classpath").isNotNull();
            return loader.load(stream, "narrowbody-twin.json");
        } catch (Exception e) {
            throw new AssertionError("could not read the fixture", e);
        }
    }

    @Test
    void loads_the_identity_and_the_weights() {
        AircraftType loaded = loadFixture();
        AircraftType builtIn = AircraftTypes.narrowbodyTwin();

        assertThat(loaded.typeCode()).isEqualTo(builtIn.typeCode());
        assertThat(loaded.name()).isEqualTo(builtIn.name());
        assertThat(loaded.engineCount()).isEqualTo(2);
        assertThat(loaded.weightLimits()).isEqualTo(builtIn.weightLimits());
    }

    @Test
    void the_cruise_tables_read_the_same_as_the_built_in_ones() {
        CruisePerformance loaded = loadFixture().cruise();
        CruisePerformance builtIn = AircraftTypes.narrowbodyTwin().cruise();

        for (double weight : new double[]{55_000, 62_000, 79_000}) {
            for (double altitude : new double[]{25_000, 33_000, 35_000, 41_000}) {
                AtmosphericConditions isa = AtmosphericConditions.isa(altitude);
                assertThat(loaded.machAt(weight, altitude))
                        .as("Mach at %.0f kg, %.0f ft", weight, altitude)
                        .isCloseTo(builtIn.machAt(weight, altitude), within(1e-9));
                assertThat(loaded.fuelFlowKgPerHour(weight, altitude, isa))
                        .as("fuel flow at %.0f kg, %.0f ft", weight, altitude)
                        .isCloseTo(builtIn.fuelFlowKgPerHour(weight, altitude, isa), within(1e-9));
            }
        }
    }

    @Test
    void the_optimum_altitude_table_reads_the_same() {
        CruisePerformance loaded = loadFixture().cruise();
        CruisePerformance builtIn = AircraftTypes.narrowbodyTwin().cruise();

        for (double weight : new double[]{55_000, 65_000, 72_000, 79_000}) {
            assertThat(loaded.optimumAltitudeFt(weight))
                    .as("optimum altitude at %.0f kg", weight)
                    .isCloseTo(builtIn.optimumAltitudeFt(weight), within(1e-9));
        }
    }

    @Test
    void the_climb_and_descent_tables_read_the_same() {
        AircraftType loaded = loadFixture();
        AircraftType builtIn = AircraftTypes.narrowbodyTwin();

        VerticalSegment loadedClimb = loaded.climb().between(0, 35_000, 70_000);
        VerticalSegment builtInClimb = builtIn.climb().between(0, 35_000, 70_000);
        assertThat(loadedClimb).isEqualTo(builtInClimb);

        assertThat(loaded.descent().between(35_000, 0, 60_000))
                .isEqualTo(builtIn.descent().between(35_000, 0, 60_000));
    }

    @Test
    void the_geometry_and_the_envelope_read_the_same() {
        AircraftType loaded = loadFixture();
        AircraftType builtIn = AircraftTypes.narrowbodyTwin();

        assertThat(loaded.armToPercentMac(19.0))
                .isCloseTo(builtIn.armToPercentMac(19.0), within(1e-9));
        assertThat(loaded.cgEnvelope().vertices()).isEqualTo(builtIn.cgEnvelope().vertices());
        assertThat(loaded.cgEnvelope().limitsAt(60_000))
                .isEqualTo(builtIn.cgEnvelope().limitsAt(60_000));
    }

    @Test
    void the_engine_out_figures_and_the_ETOPS_approval_read_the_same() {
        AircraftType loaded = loadFixture();
        AircraftType builtIn = AircraftTypes.narrowbodyTwin();

        assertThat(loaded.diversionSpeedKt()).isEqualTo(builtIn.diversionSpeedKt());
        assertThat(loaded.singleEngineCeilingFt()).isEqualTo(builtIn.singleEngineCeilingFt());
        assertThat(loaded.etopsApproval()).hasValue(120);
        assertThat(loaded.isEtopsApproved()).isTrue();
        assertThat(loaded.etopsRangeNm()).isCloseTo(builtIn.etopsRangeNm(), within(1e-9));
    }

    @Test
    void a_loaded_type_plans_a_flight_identically_to_the_built_in_one() {
        // The end-to-end check: two types that read the same must burn the same.
        io.aerodyne.weather.WeatherModel isa = io.aerodyne.weather.ConstantWeatherModel.isa();
        var request = new FlightProfileCalculator.Request(
                java.util.List.of(io.aerodyne.geo.GeoPoint.of(51.5, -0.5),
                        io.aerodyne.geo.GeoPoint.of(45.0, 5.0)),
                70_000, 35_000, 100, 100, java.time.Instant.parse("2024-06-15T08:00:00Z"));

        FlightProfile fromFile = new FlightProfileCalculator(loadFixture(), isa).compute(request);
        FlightProfile fromCode =
                new FlightProfileCalculator(AircraftTypes.narrowbodyTwin(), isa).compute(request);

        assertThat(fromFile.tripFuelKg()).isCloseTo(fromCode.tripFuelKg(), within(1e-6));
        assertThat(fromFile.tripTimeMinutes()).isCloseTo(fromCode.tripTimeMinutes(), within(1e-6));
    }

    @Test
    void derives_the_wake_category_from_the_maximum_takeoff_weight() {
        assertThat(loadFixture().wakeCategory()).isEqualTo(WakeCategory.MEDIUM);
    }

    @Test
    void a_file_may_name_a_wake_category_that_weight_alone_would_not_give() {
        // A four-engine aeroplane holds no ETOPS approval, so that goes with the engine count.
        AircraftType superHeavy = loader.load(new StringReader(minimalType()
                .replace("\"engineCount\": 2", "\"engineCount\": 4, \"wakeCategory\": \"SUPER\"")
                .replace("\"etopsApprovalMinutes\": 120,", "")), "super.json");

        assertThat(superHeavy.wakeCategory()).isEqualTo(WakeCategory.SUPER);
    }

    @Test
    void refuses_a_file_with_no_type_code() {
        assertThatThrownBy(() -> loader.load(
                new StringReader(minimalType().replace("\"typeCode\": \"TEST\",", "")), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("bad.json")
                .hasMessageContaining("'typeCode' is required");
    }

    @Test
    void refuses_a_grid_with_the_wrong_number_of_rows() {
        String broken = minimalType().replace(
                "\"fuelFlowKgPerHour\": [[2900, 2400], [3500, 2950]]",
                "\"fuelFlowKgPerHour\": [[2900, 2400]]");

        assertThatThrownBy(() -> loader.load(new StringReader(broken), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("fuelFlowKgPerHour must have 2 rows");
    }

    @Test
    void refuses_a_row_with_the_wrong_number_of_values() {
        String broken = minimalType().replace(
                "\"fuelFlowKgPerHour\": [[2900, 2400], [3500, 2950]]",
                "\"fuelFlowKgPerHour\": [[2900, 2400], [3500]]");

        assertThatThrownBy(() -> loader.load(new StringReader(broken), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("row 1 must have 2 values");
    }

    @Test
    void refuses_an_axis_that_does_not_ascend() {
        String broken = minimalType().replace(
                "\"weightsKg\": [50000, 70000],\n    \"altitudesFt\": [30000, 40000]",
                "\"weightsKg\": [70000, 50000],\n    \"altitudesFt\": [30000, 40000]");

        assertThatThrownBy(() -> loader.load(new StringReader(broken), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("must ascend");
    }

    @Test
    void refuses_weights_that_contradict_each_other() {
        String broken = minimalType().replace("\"maxZeroFuelKg\": 62700", "\"maxZeroFuelKg\": 90000");

        assertThatThrownBy(() -> loader.load(new StringReader(broken), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("weights:");
    }

    @Test
    void refuses_an_ETOPS_approval_on_something_that_is_not_a_twin() {
        String broken = minimalType().replace("\"engineCount\": 2", "\"engineCount\": 4");

        assertThatThrownBy(() -> loader.load(new StringReader(broken), "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("ETOPS applies to twin-engine aeroplanes");
    }

    @Test
    void refuses_a_missing_section_by_name() {
        assertThatThrownBy(() -> loader.load(
                new StringReader(minimalType().replaceAll("\"geometry\"", "\"geometrie\"")),
                "bad.json"))
                .isInstanceOf(AircraftTypeLoader.InvalidPerformanceDataException.class)
                .hasMessageContaining("'geometry' is required");
    }

    @Test
    void reports_which_file_the_problem_was_in() {
        AircraftTypeLoader.InvalidPerformanceDataException thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        AircraftTypeLoader.InvalidPerformanceDataException.class,
                        () -> loader.load(new StringReader("{}"), "empty.json"));

        assertThat(thrown.source()).isEqualTo("empty.json");
    }

    /** The smallest file that loads, used as a base for the failure cases. */
    private static String minimalType() {
        return """
                {
                  "typeCode": "TEST",
                  "name": "Test type",
                  "engineCount": 2,
                  "etopsApprovalMinutes": 120,
                  "weights": {
                    "operatingEmptyKg": 41500, "maxZeroFuelKg": 62700, "maxTakeoffKg": 79000,
                    "maxLandingKg": 66400, "maxFuelKg": 20900
                  },
                  "cruise": {
                    "weightsKg": [50000, 70000],
                    "altitudesFt": [30000, 40000],
                    "mach": [[0.78, 0.78], [0.78, 0.78]],
                    "fuelFlowKgPerHour": [[2900, 2400], [3500, 2950]],
                    "optimumAltitude": { "weightsKg": [50000, 70000], "altitudeFt": [39000, 35000] }
                  },
                  "climb": {
                    "weightsKg": [50000, 70000],
                    "altitudesFt": [0, 40000],
                    "minutes": [[0, 20], [0, 30]],
                    "fuelKg": [[0, 1500], [0, 2500]],
                    "distanceNm": [[0, 150], [0, 220]]
                  },
                  "descent": {
                    "weightsKg": [50000, 70000],
                    "altitudesFt": [0, 40000],
                    "minutes": [[0, 22], [0, 24]],
                    "fuelKg": [[0, 280], [0, 320]],
                    "distanceNm": [[0, 150], [0, 165]]
                  },
                  "geometry": {
                    "macLengthMetres": 4.17,
                    "macLeadingEdgeArmMetres": 17.0,
                    "operatingEmptyArmMetres": 17.88
                  },
                  "cgEnvelope": [
                    { "weightKg": 41000, "cgPercentMac": 10.0 },
                    { "weightKg": 41000, "cgPercentMac": 34.0 },
                    { "weightKg": 80000, "cgPercentMac": 28.0 },
                    { "weightKg": 80000, "cgPercentMac": 14.0 }
                  ],
                  "engineOut": {
                    "diversionSpeedKt": 380,
                    "diversionFuelFlowKgPerHour": 3300,
                    "singleEngineCeilingFt": 22000
                  }
                }
                """;
    }
}
