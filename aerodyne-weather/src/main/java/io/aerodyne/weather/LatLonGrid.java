package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;

/**
 * A regular latitude/longitude grid of one scalar field — an eastward wind component, a
 * temperature — as a GRIB2 message carries it.
 *
 * <p>The geometry follows the GRIB convention: the grid is defined by its first point and a
 * constant increment, and rows may run north-to-south (which is the usual scanning order) as
 * well as south-to-north. Values are stored row-major from that first point.
 *
 * <p>Sampling is bilinear, and wraps in longitude when the grid spans the globe — a query at
 * 179.5°E on a 1° global grid interpolates between the 179°E and 180°/-180° columns rather than
 * clamping to the edge. In latitude there is nothing to wrap to, so a query beyond the last row
 * clamps to it.
 *
 * <p>Immutable: the value array is copied on construction.
 */
public final class LatLonGrid {

    private final double firstLatitude;
    private final double firstLongitude;
    private final double latitudeIncrement;
    private final double longitudeIncrement;
    private final int columns;
    private final int rows;
    private final float[] values;
    private final boolean wrapsLongitude;

    /**
     * @param firstLatitude      latitude of the first point, degrees
     * @param firstLongitude     longitude of the first point, degrees
     * @param latitudeIncrement  degrees between rows; negative when the grid scans southward
     * @param longitudeIncrement degrees between columns; positive eastward
     * @param columns            points per row
     * @param rows               number of rows
     * @param values             row-major values, {@code columns * rows} of them
     */
    public LatLonGrid(double firstLatitude, double firstLongitude,
                      double latitudeIncrement, double longitudeIncrement,
                      int columns, int rows, float[] values) {
        if (columns < 2 || rows < 2) {
            throw new IllegalArgumentException(
                    "grid must be at least 2x2, got " + columns + "x" + rows);
        }
        if (values.length != columns * rows) {
            throw new IllegalArgumentException(
                    "expected " + (columns * rows) + " values, got " + values.length);
        }
        if (latitudeIncrement == 0.0 || longitudeIncrement == 0.0) {
            throw new IllegalArgumentException("grid increments must be non-zero");
        }
        this.firstLatitude = firstLatitude;
        this.firstLongitude = firstLongitude;
        this.latitudeIncrement = latitudeIncrement;
        this.longitudeIncrement = longitudeIncrement;
        this.columns = columns;
        this.rows = rows;
        this.values = values.clone();
        // A grid whose columns span 360 degrees has no eastern edge to clamp against.
        this.wrapsLongitude = Math.abs(columns * longitudeIncrement) >= 359.999;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public boolean wrapsLongitude() {
        return wrapsLongitude;
    }

    /** The value at a grid node, by column and row index. */
    public double valueAt(int column, int row) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) {
            throw new IndexOutOfBoundsException("node " + column + "," + row
                    + " is outside a " + columns + "x" + rows + " grid");
        }
        return values[row * columns + column];
    }

    /** The position of a grid node. */
    public GeoPoint positionAt(int column, int row) {
        return GeoPoint.of(firstLatitude + row * latitudeIncrement,
                firstLongitude + column * longitudeIncrement);
    }

    /** True when the position falls inside the grid's latitude span. */
    public boolean covers(GeoPoint position) {
        double lastLatitude = firstLatitude + (rows - 1) * latitudeIncrement;
        double south = Math.min(firstLatitude, lastLatitude);
        double north = Math.max(firstLatitude, lastLatitude);
        if (position.latitude() < south || position.latitude() > north) {
            return false;
        }
        if (wrapsLongitude) {
            return true;
        }
        double offset = columnOffset(position.longitude());
        return offset >= 0.0 && offset <= columns - 1;
    }

    /**
     * A grid of the same geometry with every value passed through {@code transform} — how a
     * field decoded in metres per second or kelvin becomes one in knots or Celsius.
     */
    public LatLonGrid transformed(java.util.function.DoubleUnaryOperator transform) {
        float[] mapped = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            mapped[i] = (float) transform.applyAsDouble(values[i]);
        }
        return new LatLonGrid(firstLatitude, firstLongitude, latitudeIncrement,
                longitudeIncrement, columns, rows, mapped);
    }

    /** Bilinear sample of the field at a position. */
    public double sample(GeoPoint position) {
        double rowOffset = clamp((position.latitude() - firstLatitude) / latitudeIncrement,
                0.0, rows - 1.0);
        double colOffset = wrapsLongitude
                ? columnOffset(position.longitude())
                : clamp(columnOffset(position.longitude()), 0.0, columns - 1.0);

        int row0 = (int) Math.floor(rowOffset);
        int col0 = (int) Math.floor(colOffset);
        int row1 = Math.min(row0 + 1, rows - 1);
        int col1 = wrapsLongitude ? (col0 + 1) % columns : Math.min(col0 + 1, columns - 1);
        double rowFraction = rowOffset - row0;
        double colFraction = colOffset - col0;

        double bottom = lerp(valueAt(col0, row0), valueAt(col1, row0), colFraction);
        double top = lerp(valueAt(col0, row1), valueAt(col1, row1), colFraction);
        return lerp(bottom, top, rowFraction);
    }

    /**
     * How many columns east of the first point a longitude lies. Longitudes are normalised into
     * the grid's own range first: a grid starting at 0°E addresses -10° as column 350 of a 1°
     * grid, not as column -10.
     */
    private double columnOffset(double longitude) {
        double delta = GeoPoint.normaliseLongitude(longitude - firstLongitude);
        if (wrapsLongitude && delta < 0.0) {
            // A global grid has no western edge either: 10°W of a grid starting at 0° is the
            // far end of the same row, not a point off the left of it.
            delta += 360.0;
        }
        return delta / longitudeIncrement;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
