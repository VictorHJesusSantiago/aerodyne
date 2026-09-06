package io.aerodyne.performance;

import java.util.Arrays;

/**
 * A two-dimensional performance table, indexed by gross weight and pressure altitude.
 *
 * <p>This is the shape aircraft performance data actually comes in: a grid of values read off
 * against weight along one axis and altitude along the other, with the reader interpolating
 * between the printed rows and columns. Everything in this module that varies with weight and
 * altitude — cruise Mach, fuel flow, climb time, descent distance — is one of these.
 *
 * <p>Queries outside the tabulated range are <em>clamped</em> to the edge rather than
 * extrapolated. A table stops where the aircraft's certified envelope stops, and a linear
 * extension past that edge would produce a number that looks like performance data and is not.
 * {@link #covers} lets a caller find out whether it got an interpolated value or an edge one.
 */
public final class PerformanceTable {

    private final String name;
    private final double[] weightsKg;
    private final double[] altitudesFt;
    private final double[][] values;

    /**
     * @param name        what the table holds, for messages
     * @param weightsKg   gross weights, strictly ascending
     * @param altitudesFt pressure altitudes, strictly ascending
     * @param values      {@code values[weightIndex][altitudeIndex]}
     */
    public PerformanceTable(String name, double[] weightsKg, double[] altitudesFt, double[][] values) {
        if (weightsKg.length == 0 || altitudesFt.length == 0) {
            throw new IllegalArgumentException(name + ": a table needs at least one weight and altitude");
        }
        requireAscending(name, "weights", weightsKg);
        requireAscending(name, "altitudes", altitudesFt);
        if (values.length != weightsKg.length) {
            throw new IllegalArgumentException(name + ": expected " + weightsKg.length
                    + " weight rows, got " + values.length);
        }
        for (double[] row : values) {
            if (row.length != altitudesFt.length) {
                throw new IllegalArgumentException(name + ": expected " + altitudesFt.length
                        + " altitude columns, got " + row.length);
            }
        }
        this.name = name;
        this.weightsKg = weightsKg.clone();
        this.altitudesFt = altitudesFt.clone();
        this.values = Arrays.stream(values).map(double[]::clone).toArray(double[][]::new);
    }

    public String name() {
        return name;
    }

    public double minimumWeightKg() {
        return weightsKg[0];
    }

    public double maximumWeightKg() {
        return weightsKg[weightsKg.length - 1];
    }

    public double minimumAltitudeFt() {
        return altitudesFt[0];
    }

    public double maximumAltitudeFt() {
        return altitudesFt[altitudesFt.length - 1];
    }

    /** True when both arguments fall inside the tabulated range, so the value is interpolated. */
    public boolean covers(double weightKg, double altitudeFt) {
        return weightKg >= minimumWeightKg() && weightKg <= maximumWeightKg()
                && altitudeFt >= minimumAltitudeFt() && altitudeFt <= maximumAltitudeFt();
    }

    /** The value at a weight and altitude, bilinearly interpolated and clamped at the edges. */
    public double valueAt(double weightKg, double altitudeFt) {
        Bracket weight = bracket(weightsKg, weightKg);
        Bracket altitude = bracket(altitudesFt, altitudeFt);

        double lower = lerp(values[weight.lowerIndex()][altitude.lowerIndex()],
                values[weight.lowerIndex()][altitude.upperIndex()], altitude.fraction());
        double upper = lerp(values[weight.upperIndex()][altitude.lowerIndex()],
                values[weight.upperIndex()][altitude.upperIndex()], altitude.fraction());
        return lerp(lower, upper, weight.fraction());
    }

    /**
     * The altitude at which the value crosses a threshold, searching upwards from the bottom of
     * the table. Used to find the highest level a weight can hold a given rate of climb at —
     * the aircraft's ceiling for that weight.
     *
     * @return the crossing altitude, or the top of the table when the value never falls below
     */
    public double altitudeWhereValueFallsBelow(double weightKg, double threshold) {
        for (int i = 0; i < altitudesFt.length; i++) {
            if (valueAt(weightKg, altitudesFt[i]) < threshold) {
                if (i == 0) {
                    return altitudesFt[0];
                }
                // Interpolate between the last altitude that met the threshold and this one.
                double above = valueAt(weightKg, altitudesFt[i - 1]);
                double below = valueAt(weightKg, altitudesFt[i]);
                double f = (above - threshold) / (above - below);
                return lerp(altitudesFt[i - 1], altitudesFt[i], f);
            }
        }
        return maximumAltitudeFt();
    }

    private static Bracket bracket(double[] axis, double value) {
        if (axis.length == 1) {
            return new Bracket(0, 0, 0.0);
        }
        if (value <= axis[0]) {
            return new Bracket(0, 0, 0.0);
        }
        if (value >= axis[axis.length - 1]) {
            int last = axis.length - 1;
            return new Bracket(last, last, 0.0);
        }
        int index = Arrays.binarySearch(axis, value);
        if (index >= 0) {
            return new Bracket(index, index, 0.0);
        }
        int upper = -index - 1;
        int lower = upper - 1;
        return new Bracket(lower, upper, (value - axis[lower]) / (axis[upper] - axis[lower]));
    }

    private static void requireAscending(String name, String what, double[] axis) {
        for (int i = 1; i < axis.length; i++) {
            if (axis[i] <= axis[i - 1]) {
                throw new IllegalArgumentException(
                        name + ": " + what + " must ascend, but " + axis[i] + " follows " + axis[i - 1]);
            }
        }
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private record Bracket(int lowerIndex, int upperIndex, double fraction) {
    }
}
