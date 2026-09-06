package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Every grid MORA record in a cycle, indexed so a position resolves in constant time.
 *
 * <p>The index is by latitude band alone, and the few records in a band are then scanned. It is
 * tempting to index on the starting longitude as well — the records are published thirty degrees
 * apart, so the band a longitude falls in looks like a division away. It is not: nothing obliges
 * a supplier to start its bands on a multiple of thirty, and a regional extract routinely does
 * not. Indexing on the assumption silently loses every record that breaks it, so the scan is
 * what is done instead. A band holds twelve records in a worldwide file, which is nothing next
 * to the walk along the route that calls it.
 */
public final class MinimumAltitudeGrid {

    /** An empty grid, for a cycle whose file carried no grid MORA records. */
    public static final MinimumAltitudeGrid EMPTY = new MinimumAltitudeGrid(List.of());

    private final Map<Integer, List<GridMora>> byLatitude;
    private final List<GridMora> records;

    public MinimumAltitudeGrid(List<GridMora> records) {
        // Insertion-ordered, so that listing the grid is the same on every run.
        Map<Integer, List<GridMora>> index = new LinkedHashMap<>();
        for (GridMora record : records) {
            index.computeIfAbsent(record.southLatitude(), band -> new java.util.ArrayList<>())
                    .add(record);
        }
        index.replaceAll((latitude, band) -> List.copyOf(band));
        this.byLatitude = Collections.unmodifiableMap(index);
        this.records = List.copyOf(records);
    }

    /** The minimum off-route altitude for the square a position falls in, when it is known. */
    public OptionalInt altitudeAt(GeoPoint position) {
        int latitude = (int) Math.floor(position.latitude());
        for (GridMora record : byLatitude.getOrDefault(latitude, List.of())) {
            if (record.covers(position)) {
                return record.altitudeAt(position);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The highest minimum off-route altitude anywhere along a path, which is the level a direct
     * routing has to be flown at or above.
     *
     * <p>Squares with no surveyed value are not treated as zero, and they are not treated as
     * infinite either: they are reported separately, through {@link Along#hasGaps()}, so the
     * caller can decide. Treating them as zero would produce a plan that descends into unsurveyed
     * terrain; treating them as infinite would refuse routes over open ocean, where a great deal
     * of the world's grid is legitimately blank.
     */
    public Along along(List<GeoPoint> path, double sampleIntervalNm) {
        int highest = GridMora.UNKNOWN;
        GeoPoint highestAt = null;
        int gaps = 0;
        int samples = 0;

        for (GeoPoint sample : samples(path, sampleIntervalNm)) {
            samples++;
            OptionalInt altitude = altitudeAt(sample);
            if (altitude.isEmpty()) {
                gaps++;
                continue;
            }
            if (altitude.getAsInt() > highest) {
                highest = altitude.getAsInt();
                highestAt = sample;
            }
        }
        return new Along(highest, highestAt, gaps, samples);
    }

    /**
     * What a walk along a route found.
     *
     * @param highestFt      the highest surveyed minimum altitude, or {@link GridMora#UNKNOWN}
     * @param highestAt      where that was, or {@code null} when nothing was surveyed
     * @param unsurveyed     how many samples fell in squares with no published value
     * @param samples        how many samples were taken
     */
    public record Along(int highestFt, GeoPoint highestAt, int unsurveyed, int samples) {

        /** True when some of the route crosses squares the grid does not cover. */
        public boolean hasGaps() {
            return unsurveyed > 0;
        }

        /** True when the grid answered for the whole route. */
        public boolean isComplete() {
            return samples > 0 && unsurveyed == 0;
        }

        public OptionalInt highest() {
            return highestFt == GridMora.UNKNOWN ? OptionalInt.empty() : OptionalInt.of(highestFt);
        }

        /** True when a cruise level clears everything the grid knows about along the route. */
        public boolean clearedBy(double altitudeFt) {
            return highestFt == GridMora.UNKNOWN || altitudeFt >= highestFt;
        }
    }

    /** Every record, in the order the file published them. */
    public List<GridMora> records() {
        return records;
    }

    public int size() {
        return records.size();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** Samples a path at an interval, always including its ends. */
    private static List<GeoPoint> samples(List<GeoPoint> path, double sampleIntervalNm) {
        List<GeoPoint> samples = new java.util.ArrayList<>();
        if (path.isEmpty()) {
            return samples;
        }
        samples.add(path.get(0));
        for (int i = 0; i < path.size() - 1; i++) {
            GeoPoint from = path.get(i);
            GeoPoint to = path.get(i + 1);
            double distance = io.aerodyne.geo.Geodesy.greatCircleDistanceNm(from, to);
            int pieces = Math.max(1, (int) Math.ceil(distance / Math.max(1.0, sampleIntervalNm)));
            for (int piece = 1; piece <= pieces; piece++) {
                samples.add(piece == pieces
                        ? to
                        : io.aerodyne.geo.Geodesy.intermediatePoint(from, to,
                                (double) piece / pieces));
            }
        }
        return samples;
    }
}
