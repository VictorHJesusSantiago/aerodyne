package io.aerodyne.dispatch.notam;

import io.aerodyne.geo.CoordinateFormat;
import io.aerodyne.geo.GeoPoint;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes NOTAMs in the ICAO format.
 *
 * <p>A NOTAM is a header and a set of lettered fields:
 *
 * <pre>
 * A1234/24 NOTAMN
 * Q) EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005
 * A) EGLL
 * B) 2406150600
 * C) 2406151800
 * E) RWY 09L/27R CLOSED
 * </pre>
 *
 * <p>The Q line is the machine-readable one and the E line is prose. This parser reads the
 * lettered fields and decodes the Q line; the E line is carried through verbatim, because
 * parsing free text written by a hundred different states is a losing proposition and the Q
 * code already says what the NOTAM does.
 *
 * <p>The field markers can appear on their own lines or run together on one, so the fields are
 * located by their {@code X)} markers rather than by line breaks. Everything after a marker
 * belongs to that field until the next marker or the end.
 */
public final class NotamParser {

    /** {@code A1234/24 NOTAMN} or {@code A1234/24 NOTAMR A1200/24}. */
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*([A-Z]\\d{4}/\\d{2})\\s+NOTAM([NRC])(?:\\s+([A-Z]\\d{4}/\\d{2}))?");
    /** The letters that introduce a field, at the start of a line or after whitespace. */
    private static final Pattern FIELD_MARKER = Pattern.compile("(?:^|\\s)([A-GQ])\\)\\s*");
    /** {@code EGTT/QMRLC/IV/NBO/A/000/999/5129N00028W005}. */
    private static final Pattern Q_LINE = Pattern.compile(
            "^([A-Z]{4})/([A-Z]{5})/([A-Z]{1,2})/([A-Z]{1,4})/([A-Z]{1,3})/(\\d{3})/(\\d{3})/(.*)$");
    /** The coordinate and radius that end a Q line: {@code 5129N00028W005}. */
    private static final Pattern Q_AREA = Pattern.compile("^(\\d{4}[NS]\\d{5}[EW])(\\d{3})$");
    /** {@code 2406150600}: a ten-digit UTC timestamp. */
    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})");

    /** Q-line limits are in hundreds of feet; 999 is the code for "no upper limit". */
    private static final int NO_UPPER_LIMIT = 999;

    /**
     * Decodes one NOTAM.
     *
     * @throws NotamParseException when the header cannot be read; without an identifier the
     *                             NOTAM cannot be tracked, replaced or cancelled
     */
    public Notam parse(String notam) {
        if (notam == null || notam.isBlank()) {
            throw new NotamParseException("empty NOTAM", notam);
        }
        String cleaned = notam.trim();
        Matcher header = HEADER.matcher(cleaned.toUpperCase(Locale.ROOT));
        if (!header.find()) {
            throw new NotamParseException("no NOTAM identifier and type", notam);
        }

        Notam.NotamType type = switch (header.group(2)) {
            case "R" -> Notam.NotamType.REPLACEMENT;
            case "C" -> Notam.NotamType.CANCELLATION;
            default -> Notam.NotamType.NEW;
        };

        Fields fields = readFields(cleaned.substring(header.end()));
        QLine q = parseQLine(fields.get("Q"));

        List<String> locations = fields.get("A") == null
                ? List.of()
                : Arrays.stream(fields.get("A").split("[\\s,]+"))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();

        Instant validFrom = parseTimestamp(fields.get("B"));
        String cField = fields.get("C");
        boolean permanent = cField != null && cField.toUpperCase(Locale.ROOT).startsWith("PERM");
        boolean estimated = cField != null && cField.toUpperCase(Locale.ROOT).contains("EST");
        Instant validTo = permanent ? null : parseTimestamp(cField);

        return new Notam(header.group(1), type, header.group(3),
                q.fir(), q.qCode(), q.traffic(), q.purpose(), q.scope(),
                q.lowerLimitFt(), q.upperLimitFt(), q.centre(), q.radiusNm(),
                locations, validFrom, validTo, estimated,
                fields.get("D"), fields.get("E"), cleaned);
    }

    /** Decodes a bulletin of several NOTAMs, skipping the ones that cannot be read. */
    public List<Notam> parseAll(List<String> notams) {
        List<Notam> parsed = new ArrayList<>(notams.size());
        for (String notam : notams) {
            try {
                parsed.add(parse(notam));
            } catch (NotamParseException unreadable) {
                // One malformed NOTAM in a bulletin must not cost the rest of the briefing.
                continue;
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * Splits the body into its lettered fields. Markers are found wherever they appear rather
     * than only at line starts, because a NOTAM that has been through a teleprinter, an email
     * client and a web page rarely keeps its line breaks.
     */
    private static Fields readFields(String body) {
        Fields fields = new Fields();
        Matcher marker = FIELD_MARKER.matcher(body);
        String currentField = null;
        int contentStart = 0;

        while (marker.find()) {
            if (currentField != null) {
                fields.put(currentField, body.substring(contentStart, marker.start()));
            }
            currentField = marker.group(1);
            contentStart = marker.end();
        }
        if (currentField != null) {
            fields.put(currentField, body.substring(contentStart));
        }
        return fields;
    }

    private static QLine parseQLine(String line) {
        if (line == null) {
            return QLine.empty();
        }
        Matcher matcher = Q_LINE.matcher(line.trim().replaceAll("\\s+", ""));
        if (!matcher.matches()) {
            return QLine.empty();
        }
        int lower = Integer.parseInt(matcher.group(6)) * 100;
        int upperRaw = Integer.parseInt(matcher.group(7));
        Integer upper = upperRaw >= NO_UPPER_LIMIT ? null : upperRaw * 100;

        GeoPoint centre = null;
        Double radius = null;
        Matcher area = Q_AREA.matcher(matcher.group(8).trim());
        if (area.matches()) {
            centre = CoordinateFormat.parseOrNull(area.group(1));
            radius = (double) Integer.parseInt(area.group(2));
        }

        return new QLine(matcher.group(1), QCode.parse(matcher.group(2)), matcher.group(3),
                matcher.group(4), matcher.group(5), lower, upper, centre, radius);
    }

    /**
     * Reads a {@code YYMMDDHHMM} timestamp. Two-digit years are read as this century, which is
     * what the format assumes and what every publisher of it means.
     */
    private static Instant parseTimestamp(String field) {
        if (field == null) {
            return null;
        }
        Matcher matcher = TIMESTAMP.matcher(field.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDateTime.of(
                            2000 + Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3)),
                            Integer.parseInt(matcher.group(4)),
                            Integer.parseInt(matcher.group(5)))
                    .toInstant(ZoneOffset.UTC);
        } catch (java.time.DateTimeException notADate) {
            return null;
        }
    }

    /** The lettered fields of one NOTAM, trimmed. */
    private static final class Fields {

        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        void put(String field, String content) {
            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                values.put(field, trimmed);
            }
        }

        String get(String field) {
            return values.get(field);
        }
    }

    private record QLine(String fir, QCode qCode, String traffic, String purpose, String scope,
                         Integer lowerLimitFt, Integer upperLimitFt, GeoPoint centre, Double radiusNm) {

        static QLine empty() {
            return new QLine(null, QCode.UNKNOWN, null, null, null, null, null, null, null);
        }
    }

    /** Raised when a NOTAM has no readable identifier. */
    public static class NotamParseException extends RuntimeException {

        private final String notam;

        public NotamParseException(String message, String notam) {
            super(message + ": '" + notam + "'");
            this.notam = notam;
        }

        public String notam() {
            return notam;
        }
    }
}
