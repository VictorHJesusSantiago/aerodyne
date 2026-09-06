package io.aerodyne.performance;

import java.util.List;

/**
 * Two worked aircraft types, so that the engine has something to plan with.
 *
 * <p><b>These are not manufacturer data</b>, and they say so: both are marked
 * {@link DataProvenance#REPRESENTATIVE}, which makes any plan built on them undispatchable
 * rather than merely documented as unfit. The numbers are representative of the classes of
 * aeroplane they describe — a 79-tonne narrowbody twin and a 228-tonne widebody twin — and they
 * are internally consistent, but they are constructed for this project rather than taken from a
 * flight manual. They are here so the fuel integration can be exercised and demonstrated end to
 * end; a real operation loads its own tables, which is what {@link AircraftType} exists to hold.
 *
 * <p>The tables are laid out the way published ones are: cumulative from sea level for climb and
 * descent, and read against weight and pressure altitude for cruise.
 */
public final class AircraftTypes {

    private AircraftTypes() {
    }

    /** A twin-engine narrowbody of about 79 tonnes, approved for 120-minute diversions. */
    public static AircraftType narrowbodyTwin() {
        double[] weights = {55_000, 79_000};
        double[] cruiseAltitudes = {25_000, 31_000, 35_000, 39_000, 41_000};
        double[] verticalAltitudes = {0, 10_000, 20_000, 30_000, 35_000, 39_000, 41_000};

        CruisePerformance cruise = new CruisePerformance(
                new PerformanceTable("narrowbody cruise Mach", weights, cruiseAltitudes,
                        new double[][]{
                                {0.780, 0.785, 0.785, 0.780, 0.775},
                                {0.780, 0.782, 0.780, 0.770, 0.760},
                        }),
                new PerformanceTable("narrowbody fuel flow kg/h", weights, cruiseAltitudes,
                        new double[][]{
                                {2900, 2600, 2400, 2280, 2260},
                                {3500, 3150, 2950, 2900, 3000},
                        }),
                // A turbofan burns rather under half a percent more per degree above standard.
                0.004,
                new PerformanceTable("narrowbody optimum altitude",
                        new double[]{55_000, 65_000, 79_000}, new double[]{0},
                        new double[][]{{39_500}, {37_000}, {33_500}}));

        VerticalPerformance climb = new VerticalPerformance(
                new PerformanceTable("narrowbody climb minutes", weights, verticalAltitudes,
                        new double[][]{
                                {0, 3.5, 7.5, 13.0, 17.0, 22.5, 27.0},
                                {0, 4.5, 10.0, 18.0, 24.0, 33.0, 41.0},
                        }),
                new PerformanceTable("narrowbody climb fuel kg", weights, verticalAltitudes,
                        new double[][]{
                                {0, 350, 700, 1150, 1450, 1800, 2100},
                                {0, 450, 950, 1650, 2150, 2800, 3400},
                        }),
                new PerformanceTable("narrowbody climb distance nm", weights, verticalAltitudes,
                        new double[][]{
                                {0, 22, 52, 100, 137, 190, 235},
                                {0, 28, 70, 140, 195, 280, 355},
                        }));

        double[] landingWeights = {50_000, 66_400};
        VerticalPerformance descent = new VerticalPerformance(
                new PerformanceTable("narrowbody descent minutes", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 6.0, 11.5, 17.0, 20.5, 24.0, 26.0},
                                {0, 6.5, 12.5, 18.5, 22.0, 26.0, 28.0},
                        }),
                new PerformanceTable("narrowbody descent fuel kg", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 70, 130, 190, 230, 270, 295},
                                {0, 80, 150, 215, 260, 305, 335},
                        }),
                new PerformanceTable("narrowbody descent distance nm", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 28, 60, 95, 118, 142, 158},
                                {0, 30, 65, 103, 128, 155, 172},
                        }));

        // The envelope is drawn a little beyond the structural limit, as published ones are,
        // so that a loading exactly at maximum weight still has limits either side of it.
        CentreOfGravityEnvelope envelope = new CentreOfGravityEnvelope("narrowbody twin",
                List.of(new CentreOfGravityEnvelope.Vertex(41_000, 10.0),
                        new CentreOfGravityEnvelope.Vertex(41_000, 34.0),
                        new CentreOfGravityEnvelope.Vertex(62_700, 32.0),
                        new CentreOfGravityEnvelope.Vertex(80_000, 28.0),
                        new CentreOfGravityEnvelope.Vertex(80_000, 14.0),
                        new CentreOfGravityEnvelope.Vertex(62_700, 9.0)));

        return new AircraftType("N738", "Narrowbody twin", WakeCategory.MEDIUM, 2,
                new WeightLimits(41_500, 62_700, 79_000, 66_400, 20_900),
                cruise, climb, descent, envelope,
                4.17, 17.00, 17.88,
                380.0, 3_300.0, 22_000.0, 120, DataProvenance.REPRESENTATIVE);
    }

    /** A twin-engine widebody of about 228 tonnes, approved for 330-minute diversions. */
    public static AircraftType widebodyTwin() {
        double[] weights = {160_000, 228_000};
        double[] cruiseAltitudes = {29_000, 33_000, 35_000, 39_000, 43_000};
        double[] verticalAltitudes = {0, 10_000, 20_000, 30_000, 35_000, 39_000, 43_000};

        CruisePerformance cruise = new CruisePerformance(
                new PerformanceTable("widebody cruise Mach", weights, cruiseAltitudes,
                        new double[][]{
                                {0.850, 0.852, 0.853, 0.850, 0.840},
                                {0.848, 0.850, 0.850, 0.840, 0.820},
                        }),
                new PerformanceTable("widebody fuel flow kg/h", weights, cruiseAltitudes,
                        new double[][]{
                                {6300, 5800, 5600, 5350, 5400},
                                {7400, 6900, 6700, 6600, 7000},
                        }),
                0.004,
                new PerformanceTable("widebody optimum altitude",
                        new double[]{160_000, 195_000, 228_000}, new double[]{0},
                        new double[][]{{41_000}, {38_500}, {35_000}}));

        VerticalPerformance climb = new VerticalPerformance(
                new PerformanceTable("widebody climb minutes", weights, verticalAltitudes,
                        new double[][]{
                                {0, 4.0, 8.5, 15.0, 19.5, 25.0, 34.0},
                                {0, 5.5, 12.0, 21.0, 28.0, 37.0, 52.0},
                        }),
                new PerformanceTable("widebody climb fuel kg", weights, verticalAltitudes,
                        new double[][]{
                                {0, 900, 1800, 2900, 3600, 4400, 5600},
                                {0, 1200, 2500, 4200, 5300, 6700, 8800},
                        }),
                new PerformanceTable("widebody climb distance nm", weights, verticalAltitudes,
                        new double[][]{
                                {0, 26, 60, 115, 155, 210, 300},
                                {0, 34, 84, 165, 230, 320, 470},
                        }));

        double[] landingWeights = {140_000, 172_000};
        VerticalPerformance descent = new VerticalPerformance(
                new PerformanceTable("widebody descent minutes", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 6.5, 12.5, 18.5, 22.0, 25.5, 30.0},
                                {0, 7.0, 13.5, 20.0, 24.0, 28.0, 33.0},
                        }),
                new PerformanceTable("widebody descent fuel kg", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 180, 340, 490, 590, 690, 820},
                                {0, 200, 380, 550, 660, 770, 910},
                        }),
                new PerformanceTable("widebody descent distance nm", landingWeights, verticalAltitudes,
                        new double[][]{
                                {0, 32, 70, 112, 140, 168, 205},
                                {0, 34, 76, 122, 152, 183, 224},
                        }));

        CentreOfGravityEnvelope envelope = new CentreOfGravityEnvelope("widebody twin",
                List.of(new CentreOfGravityEnvelope.Vertex(119_000, 12.0),
                        new CentreOfGravityEnvelope.Vertex(119_000, 33.0),
                        new CentreOfGravityEnvelope.Vertex(161_000, 31.0),
                        new CentreOfGravityEnvelope.Vertex(232_000, 27.0),
                        new CentreOfGravityEnvelope.Vertex(232_000, 16.0),
                        new CentreOfGravityEnvelope.Vertex(161_000, 11.0)));

        return new AircraftType("W788", "Widebody twin", WakeCategory.HEAVY, 2,
                new WeightLimits(120_000, 161_000, 228_000, 172_000, 101_000),
                cruise, climb, descent, envelope,
                6.50, 27.00, 28.60,
                400.0, 7_500.0, 25_000.0, 330, DataProvenance.REPRESENTATIVE);
    }
}
