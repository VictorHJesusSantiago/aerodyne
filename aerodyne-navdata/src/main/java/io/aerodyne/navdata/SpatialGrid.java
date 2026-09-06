package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A one-degree bucket grid over the fixes, for "what is near here" queries.
 *
 * <p>A full navdata cycle holds on the order of a hundred thousand fixes. Scanning all of them
 * to find the ones within 200 nm of a position is fast enough once and far too slow inside the
 * route search, which asks the question at every expanded node. Bucketing by whole degree cuts
 * that to the handful of cells the search radius touches.
 *
 * <p>Cells are one degree of latitude by one of longitude, so they narrow towards the poles.
 * That makes them uneven in area but never <em>too small</em> for a given radius, which is the
 * direction that would cost correctness: the cell span is computed from the radius at the query
 * latitude, and the results are filtered by true distance afterwards.
 *
 * <p>Immutable once built, and safe to share between threads.
 */
public final class SpatialGrid<T> {

    private final Map<Long, List<T>> cells;
    private final Function<T, GeoPoint> locator;
    private final int size;

    private SpatialGrid(Map<Long, List<T>> cells, Function<T, GeoPoint> locator, int size) {
        this.cells = cells;
        this.locator = locator;
        this.size = size;
    }

    /** Buckets the given items, reading each one's position through {@code locator}. */
    public static <T> SpatialGrid<T> of(Iterable<T> items, Function<T, GeoPoint> locator) {
        // A LinkedHashMap, not a HashMap: two fixes at the same distance from a query must come
        // back in the same order on every run, or a plan stops being reproducible.
        Map<Long, List<T>> cells = new java.util.LinkedHashMap<>();
        int count = 0;
        for (T item : items) {
            GeoPoint position = locator.apply(item);
            if (position == null) {
                continue;
            }
            cells.computeIfAbsent(cellKey(position), k -> new ArrayList<>()).add(item);
            count++;
        }
        cells.replaceAll((key, bucket) -> List.copyOf(bucket));
        return new SpatialGrid<>(java.util.Collections.unmodifiableMap(cells), locator, count);
    }

    public int size() {
        return size;
    }

    public int cellCount() {
        return cells.size();
    }

    /** Everything within {@code radiusNm} of {@code centre}, nearest first. */
    public List<T> within(GeoPoint centre, double radiusNm) {
        return within(centre, radiusNm, Integer.MAX_VALUE);
    }

    /** The {@code limit} nearest items within {@code radiusNm} of {@code centre}. */
    public List<T> within(GeoPoint centre, double radiusNm, int limit) {
        if (radiusNm <= 0 || limit <= 0) {
            return List.of();
        }
        List<T> candidates = new ArrayList<>();
        for (long key : cellKeysCovering(centre, radiusNm)) {
            List<T> bucket = cells.get(key);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates.stream()
                .map(item -> Map.entry(item, Geodesy.greatCircleDistanceNm(centre, locator.apply(item))))
                .filter(entry -> entry.getValue() <= radiusNm)
                .sorted(Map.Entry.comparingByValue())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The single nearest item within the radius, or empty when the area holds none. */
    public java.util.Optional<T> nearest(GeoPoint centre, double radiusNm) {
        return within(centre, radiusNm, 1).stream().findFirst();
    }

    /**
     * The item of {@code candidates} closest to {@code reference}. Used to pick between the
     * several fixes that share an identifier once the leg they belong to is known.
     */
    public static <T> java.util.Optional<T> closestTo(GeoPoint reference,
                                                      List<T> candidates,
                                                      Function<T, GeoPoint> locator) {
        return candidates.stream()
                .min(Comparator.comparingDouble(
                        c -> Geodesy.greatCircleDistanceNm(reference, locator.apply(c))));
    }

    /**
     * The cells a circle of {@code radiusNm} around {@code centre} can reach.
     *
     * <p>A degree of latitude is 60 nm everywhere; a degree of longitude is 60 nm times the
     * cosine of the latitude, so the longitude span widens as the query moves poleward. Near
     * the pole the cosine collapses and the span would cover the globe, so it is capped at a
     * full sweep of longitude rather than allowed to run away.
     */
    private List<Long> cellKeysCovering(GeoPoint centre, double radiusNm) {
        int latSpan = (int) Math.ceil(radiusNm / 60.0);
        double cosLatitude = Math.cos(Math.toRadians(Math.abs(centre.latitude())));
        int lonSpan = cosLatitude < 1e-6
                ? 180
                : Math.min(180, (int) Math.ceil(radiusNm / (60.0 * cosLatitude)));

        int centreLat = (int) Math.floor(centre.latitude());
        int centreLon = (int) Math.floor(centre.longitude());

        List<Long> keys = new ArrayList<>((2 * latSpan + 1) * (2 * lonSpan + 1));
        for (int dLat = -latSpan; dLat <= latSpan; dLat++) {
            int lat = centreLat + dLat;
            if (lat < -90 || lat > 90) {
                continue;
            }
            for (int dLon = -lonSpan; dLon <= lonSpan; dLon++) {
                // Wrap across the antimeridian so a query near 180E still sees 179W.
                int lon = Math.floorMod(centreLon + dLon + 180, 360) - 180;
                keys.add(key(lat, lon));
            }
        }
        return keys;
    }

    private static long cellKey(GeoPoint position) {
        return key((int) Math.floor(position.latitude()), (int) Math.floor(position.longitude()));
    }

    private static long key(int latitudeDegrees, int longitudeDegrees) {
        return ((long) (latitudeDegrees + 90) << 32) | (longitudeDegrees + 180);
    }
}
