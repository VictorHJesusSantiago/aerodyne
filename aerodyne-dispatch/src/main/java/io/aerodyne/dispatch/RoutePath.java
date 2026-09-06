package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;

import java.util.ArrayList;
import java.util.List;

/**
 * A route treated as a continuous curve, so that things can be located <em>along</em> it rather
 * than only at its published points.
 *
 * <p>Almost everything dispatch computes is a position along the route that no waypoint marks:
 * the ETOPS entry point, an equal-time point, the place where the route is furthest from any
 * adequate aerodrome. All of those are found by searching in distance-along-track, so they need
 * a route that answers "where am I after 812 nautical miles" — which is what this provides.
 *
 * <p>Legs are followed as great circles, and a query inside a leg is interpolated along it.
 */
public final class RoutePath {

    private final List<GeoPoint> points;
    private final double[] cumulativeNm;

    public RoutePath(List<GeoPoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("a route path needs at least two points");
        }
        this.points = List.copyOf(points);
        this.cumulativeNm = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulativeNm[i] = cumulativeNm[i - 1]
                    + Geodesy.distanceNm(points.get(i - 1), points.get(i));
        }
        if (totalDistanceNm() <= 0.0) {
            throw new IllegalArgumentException("a route path must have some length");
        }
    }

    public List<GeoPoint> points() {
        return points;
    }

    public double totalDistanceNm() {
        return cumulativeNm[cumulativeNm.length - 1];
    }

    public GeoPoint start() {
        return points.get(0);
    }

    public GeoPoint end() {
        return points.get(points.size() - 1);
    }

    /** The position a given distance along the route, clamped to its ends. */
    public GeoPoint pointAtNm(double distanceAlongNm) {
        if (distanceAlongNm <= 0.0) {
            return start();
        }
        if (distanceAlongNm >= totalDistanceNm()) {
            return end();
        }
        int leg = legContaining(distanceAlongNm);
        double into = distanceAlongNm - cumulativeNm[leg];
        double legLength = cumulativeNm[leg + 1] - cumulativeNm[leg];
        return legLength <= 0.0
                ? points.get(leg)
                : Geodesy.intermediatePoint(points.get(leg), points.get(leg + 1), into / legLength);
    }

    /** The track being flown a given distance along the route. */
    public double courseAtNm(double distanceAlongNm) {
        int leg = legContaining(Math.max(0.0, Math.min(distanceAlongNm, totalDistanceNm() - 1e-9)));
        return Geodesy.initialBearingDeg(points.get(leg), points.get(leg + 1));
    }

    /**
     * The route sampled at roughly regular intervals, always including both ends.
     *
     * <p>Used by everything that has to scan the whole route for a worst case — the furthest
     * point from an adequate aerodrome, the highest terrain, the coldest fuel temperature.
     */
    public List<Sample> sample(double intervalNm) {
        if (intervalNm <= 0.0) {
            throw new IllegalArgumentException("sampling interval must be positive");
        }
        int steps = Math.max(1, (int) Math.ceil(totalDistanceNm() / intervalNm));
        List<Sample> samples = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double along = totalDistanceNm() * i / steps;
            samples.add(new Sample(along, pointAtNm(along)));
        }
        return List.copyOf(samples);
    }

    /** One sampled position: how far along the route it is, and where that is. */
    public record Sample(double distanceAlongNm, GeoPoint position) {
    }

    private int legContaining(double distanceAlongNm) {
        for (int i = 0; i < cumulativeNm.length - 1; i++) {
            if (distanceAlongNm <= cumulativeNm[i + 1]) {
                return i;
            }
        }
        return cumulativeNm.length - 2;
    }
}
