package io.aerodyne.performance;

import java.util.List;

/**
 * The certified centre-of-gravity envelope: which centres of gravity are permitted at which
 * weights.
 *
 * <p>The envelope is a closed polygon in the plane of weight against centre of gravity, and it
 * is not a rectangle — the permitted forward limit typically moves aft as weight increases,
 * because the elevator authority that holds the nose up on rotation runs out sooner when the
 * aircraft is heavy. So the check has to be a point-in-polygon test rather than a pair of
 * comparisons, and the vertices are given in order around the boundary.
 *
 * <p>Centres of gravity are expressed as a percentage of the mean aerodynamic chord, which is
 * how a loadsheet states them and how the envelope is published.
 */
public record CentreOfGravityEnvelope(String name, List<Vertex> vertices) {

    /**
     * One corner of the envelope.
     *
     * @param weightKg      the weight at which this corner sits
     * @param cgPercentMac  the centre of gravity there, as a percentage of the mean aerodynamic chord
     */
    public record Vertex(double weightKg, double cgPercentMac) {
    }

    public CentreOfGravityEnvelope {
        vertices = List.copyOf(vertices);
        if (vertices.size() < 3) {
            throw new IllegalArgumentException(
                    "an envelope needs at least three vertices, got " + vertices.size());
        }
    }

    /**
     * True when a loaded weight and centre of gravity fall inside the envelope.
     *
     * <p>A ray-casting test: count how many times a ray cast in one direction from the point
     * crosses the boundary. An odd count means the point is inside. Points exactly on an edge
     * are treated as inside, since a loadsheet that lands precisely on the limit is legal.
     */
    public boolean contains(double weightKg, double cgPercentMac) {
        if (isOnBoundary(weightKg, cgPercentMac)) {
            return true;
        }
        boolean inside = false;
        int count = vertices.size();
        for (int i = 0, j = count - 1; i < count; j = i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get(j);
            boolean straddles = (a.weightKg() > weightKg) != (b.weightKg() > weightKg);
            if (!straddles) {
                continue;
            }
            double crossingCg = a.cgPercentMac()
                    + (weightKg - a.weightKg()) / (b.weightKg() - a.weightKg())
                    * (b.cgPercentMac() - a.cgPercentMac());
            if (cgPercentMac < crossingCg) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * The forward and aft limits at a weight, or empty when the weight is outside the envelope
     * entirely. This is what a loadsheet prints beside the actual centre of gravity, so that
     * the margin is visible rather than merely the verdict.
     */
    public java.util.Optional<Limits> limitsAt(double weightKg) {
        Double forward = null;
        Double aft = null;
        int count = vertices.size();
        for (int i = 0, j = count - 1; i < count; j = i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get(j);
            if ((a.weightKg() > weightKg) == (b.weightKg() > weightKg)) {
                continue;
            }
            double crossingCg = a.cgPercentMac()
                    + (weightKg - a.weightKg()) / (b.weightKg() - a.weightKg())
                    * (b.cgPercentMac() - a.cgPercentMac());
            forward = forward == null ? crossingCg : Math.min(forward, crossingCg);
            aft = aft == null ? crossingCg : Math.max(aft, crossingCg);
        }
        return forward == null ? java.util.Optional.empty() : java.util.Optional.of(new Limits(forward, aft));
    }

    /** The permitted range of centre of gravity at one weight. */
    public record Limits(double forwardPercentMac, double aftPercentMac) {

        public double widthPercentMac() {
            return aftPercentMac - forwardPercentMac;
        }
    }

    public double minimumWeightKg() {
        return vertices.stream().mapToDouble(Vertex::weightKg).min().orElseThrow();
    }

    public double maximumWeightKg() {
        return vertices.stream().mapToDouble(Vertex::weightKg).max().orElseThrow();
    }

    private boolean isOnBoundary(double weightKg, double cgPercentMac) {
        int count = vertices.size();
        for (int i = 0, j = count - 1; i < count; j = i++) {
            if (isOnSegment(vertices.get(j), vertices.get(i), weightKg, cgPercentMac)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnSegment(Vertex a, Vertex b, double weightKg, double cgPercentMac) {
        double cross = (b.weightKg() - a.weightKg()) * (cgPercentMac - a.cgPercentMac())
                - (b.cgPercentMac() - a.cgPercentMac()) * (weightKg - a.weightKg());
        // The tolerance is scaled to the weight axis, whose numbers are five orders of
        // magnitude larger than the percentage axis.
        if (Math.abs(cross) > 1e-6 * Math.max(1.0, Math.abs(b.weightKg() - a.weightKg()))) {
            return false;
        }
        return weightKg >= Math.min(a.weightKg(), b.weightKg()) - 1e-9
                && weightKg <= Math.max(a.weightKg(), b.weightKg()) + 1e-9
                && cgPercentMac >= Math.min(a.cgPercentMac(), b.cgPercentMac()) - 1e-9
                && cgPercentMac <= Math.max(a.cgPercentMac(), b.cgPercentMac()) + 1e-9;
    }
}
