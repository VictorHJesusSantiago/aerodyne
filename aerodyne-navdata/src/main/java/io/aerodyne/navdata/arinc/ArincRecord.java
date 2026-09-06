package io.aerodyne.navdata.arinc;

import io.aerodyne.geo.CoordinateFormat;
import io.aerodyne.geo.GeoPoint;

import java.util.Locale;

/**
 * One line of an ARINC 424 file.
 *
 * <p>The format is fixed-column: every record is 132 characters and each field lives at a
 * documented offset. Real files are ragged — trailing blanks get stripped by whatever moved the
 * file around, and some suppliers pad to 133 — so every accessor here clamps to the line length
 * and returns a blank rather than throwing. A short line yields empty fields, which the parser
 * reports as an issue instead of failing the whole file.
 *
 * <p>Columns are given the way the specification numbers them: <b>1-indexed and inclusive</b>.
 */
public final class ArincRecord {

    /** The specified record length; lines are neither required nor assumed to reach it. */
    public static final int RECORD_LENGTH = 132;

    private final String line;
    private final int lineNumber;

    public ArincRecord(String line, int lineNumber) {
        this.line = line == null ? "" : line;
        this.lineNumber = lineNumber;
    }

    public String line() {
        return line;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public int length() {
        return line.length();
    }

    /** True for a line that cannot carry the fields the parser needs. */
    public boolean isTruncated() {
        return line.length() < 40;
    }

    /**
     * The field between two columns, inclusive, with surrounding blanks removed.
     * Returns {@code ""} when the line stops short of the field.
     */
    public String field(int startColumn, int endColumn) {
        if (startColumn < 1 || endColumn < startColumn) {
            throw new IllegalArgumentException("bad column range " + startColumn + ".." + endColumn);
        }
        int from = Math.min(startColumn - 1, line.length());
        int to = Math.min(endColumn, line.length());
        return from >= to ? "" : line.substring(from, to).trim();
    }

    /** The field between two columns with its blanks kept, for fields where they are data. */
    public String rawField(int startColumn, int endColumn) {
        int from = Math.min(startColumn - 1, line.length());
        int to = Math.min(endColumn, line.length());
        return from >= to ? "" : line.substring(from, to);
    }

    /** A single column, or {@code ' '} when the line is shorter than that. */
    public char charAt(int column) {
        return column >= 1 && column <= line.length() ? line.charAt(column - 1) : ' ';
    }

    /** An integer field, or {@code null} when it is blank or not a number. */
    public Integer intField(int startColumn, int endColumn) {
        String raw = field(startColumn, endColumn);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            // Some suppliers zero-fill and others blank-fill; both parse once trimmed.
            return Integer.valueOf(raw.replace("+", ""), 10);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** An integer field, falling back to {@code defaultValue} when blank or unparseable. */
    public int intField(int startColumn, int endColumn, int defaultValue) {
        Integer value = intField(startColumn, endColumn);
        return value == null ? defaultValue : value;
    }

    /**
     * A signed field written with a leading sign character, as magnetic variation and runway
     * gradient are: {@code E0006} or {@code -0250}. The scale divides the digits, so a
     * variation field written in tenths of a degree is read with {@code scale = 10}.
     *
     * <p>{@code E} and {@code +} are positive; {@code W} and {@code -} negative.
     */
    public Double signedField(int startColumn, int endColumn, double scale) {
        String raw = field(startColumn, endColumn);
        if (raw.length() < 2) {
            return null;
        }
        char sign = Character.toUpperCase(raw.charAt(0));
        double factor = switch (sign) {
            case 'E', '+' -> 1.0;
            case 'W', '-' -> -1.0;
            default -> Double.NaN;
        };
        if (Double.isNaN(factor)) {
            return null;
        }
        try {
            return factor * Integer.parseInt(raw.substring(1)) / scale;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * The 19-character packed position: {@code N51285900W000002740}. Latitude occupies nine
     * columns and longitude the ten that follow.
     *
     * @return the position, or {@code null} when the field is blank or malformed
     */
    public GeoPoint positionField(int latitudeStartColumn) {
        String packed = rawField(latitudeStartColumn, latitudeStartColumn + 18);
        if (packed.isBlank()) {
            return null;
        }
        return CoordinateFormat.parseOrNull(packed.replace(" ", ""));
    }

    /** The section code, column 5 — {@code P} airport, {@code E} enroute, {@code D} navaid. */
    public char sectionCode() {
        return Character.toUpperCase(charAt(5));
    }

    /**
     * The subsection code. Airport and heliport records carry it at column 13; every other
     * section carries it at column 6. Getting this wrong is the classic way to mis-file a
     * runway record as an airport one, so it lives here rather than in each caller.
     */
    public char subsectionCode() {
        char section = sectionCode();
        return Character.toUpperCase(section == 'P' || section == 'H' ? charAt(13) : charAt(6));
    }

    /** The two-character code identifying which supplier or state issued the record. */
    public String customerAreaCode() {
        return field(2, 4).toUpperCase(Locale.ROOT);
    }

    /** True for a header record, which describes the file rather than a navigation object. */
    public boolean isHeader() {
        return charAt(1) == 'H';
    }

    /**
     * True for a continuation record: the primary record is number {@code 0} or {@code 1} and
     * anything higher continues it. Continuations carry secondary data the parser does not need.
     */
    public boolean isContinuation() {
        return isContinuation(22);
    }

    /**
     * True for a continuation record, reading the continuation number from a given column.
     *
     * <p>Most sections put it at column 22, but the procedure sections put it at 39. Reading it
     * from the wrong column is how a procedure's primary records get mistaken for continuations
     * and the whole procedure vanishes, so the column is a parameter rather than an assumption.
     */
    public boolean isContinuation(int column) {
        char continuationNumber = charAt(column);
        return continuationNumber != ' ' && continuationNumber != '0' && continuationNumber != '1';
    }

    @Override
    public String toString() {
        return "line " + lineNumber + ": " + line;
    }
}
