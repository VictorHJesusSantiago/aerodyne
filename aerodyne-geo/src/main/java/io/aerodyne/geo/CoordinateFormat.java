package io.aerodyne.geo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the coordinate notations that appear in navigation data and filed routes.
 *
 * <p>A single route string can mix all of them, so {@link #parse(String)} tries each in turn:
 *
 * <table>
 *   <caption>Recognised notations</caption>
 *   <tr><th>Form</th><th>Example</th><th>Where it comes from</th></tr>
 *   <tr><td>ARINC 424 packed</td><td>{@code N51300000W000073000}</td><td>navdata record fields</td></tr>
 *   <tr><td>Degrees and decimal minutes</td><td>{@code N5130.0W00007.5}</td><td>OFP listings, FMS entry</td></tr>
 *   <tr><td>Degrees, minutes, seconds</td><td>{@code 513000N 0000730W}</td><td>AIP tables</td></tr>
 *   <tr><td>ICAO whole-degree</td><td>{@code 51N007W}</td><td>filed ATS flight plans</td></tr>
 *   <tr><td>Oceanic quadrant shorthand</td><td>{@code 5130N}</td><td>NAT track messages</td></tr>
 *   <tr><td>Signed decimal degrees</td><td>{@code 51.5/-0.125}</td><td>internal and API payloads</td></tr>
 * </table>
 */
public final class CoordinateFormat {

    /** {@code N51300000W000073000} — hemisphere, then ddmmssss / dddmmssss (hundredths of a second). */
    private static final Pattern ARINC_PACKED = Pattern.compile(
            "^([NS])(\\d{2})(\\d{2})(\\d{2})(\\d{2})([EW])(\\d{3})(\\d{2})(\\d{2})(\\d{2})$");

    /** {@code N5130.0W00007.5} — hemisphere, degrees, decimal minutes. */
    private static final Pattern DEGREES_DECIMAL_MINUTES = Pattern.compile(
            "^([NS])(\\d{2})(\\d{2}(?:\\.\\d+)?)\\s*([EW])(\\d{3})(\\d{2}(?:\\.\\d+)?)$");

    /** {@code 513000N 0000730W} — degrees, minutes, seconds, trailing hemisphere. */
    private static final Pattern DMS_TRAILING = Pattern.compile(
            "^(\\d{2})(\\d{2})(\\d{2}(?:\\.\\d+)?)([NS])\\s*(\\d{3})(\\d{2})(\\d{2}(?:\\.\\d+)?)([EW])$");

    /** {@code 5130N00007W} or {@code 51N007W} — the ICAO ATS flight-plan forms. */
    private static final Pattern ICAO_DEGREES_MINUTES = Pattern.compile(
            "^(\\d{2})(\\d{2})([NS])(\\d{3})(\\d{2})([EW])$");
    private static final Pattern ICAO_WHOLE_DEGREES = Pattern.compile(
            "^(\\d{2})([NS])(\\d{3})([EW])$");

    /** {@code 5130N} — oceanic shorthand, latitude then longitude, quadrant in the letter. */
    private static final Pattern OCEANIC_SHORTHAND = Pattern.compile("^(\\d{2})(\\d{2})([NESW])$");

    /** {@code 51.5/-0.125} — the internal form, and what the REST API accepts. */
    private static final Pattern SIGNED_DECIMAL = Pattern.compile(
            "^(-?\\d+(?:\\.\\d+)?)\\s*[/,]\\s*(-?\\d+(?:\\.\\d+)?)$");

    private CoordinateFormat() {
    }

    /**
     * Parses any recognised notation.
     *
     * @throws IllegalArgumentException if no notation matches, or the values are out of range
     */
    public static GeoPoint parse(String text) {
        GeoPoint parsed = parseOrNull(text);
        if (parsed == null) {
            throw new IllegalArgumentException("unrecognised coordinate: '" + text + "'");
        }
        return parsed;
    }

    /**
     * Parses any recognised notation, returning {@code null} rather than throwing.
     *
     * <p>The route parser leans on this: a route token is a coordinate if it parses and a named
     * fix otherwise, and "does not parse" is the normal case rather than an error.
     */
    public static GeoPoint parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().toUpperCase(Locale.ROOT).replace("°", "");
        if (s.isEmpty()) {
            return null;
        }

        Matcher m = ARINC_PACKED.matcher(s);
        if (m.matches()) {
            double lat = dms(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)) + Integer.parseInt(m.group(5)) / 100.0);
            double lon = dms(Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)),
                    Integer.parseInt(m.group(9)) + Integer.parseInt(m.group(10)) / 100.0);
            return signed(lat, m.group(1), lon, m.group(6));
        }

        m = DEGREES_DECIMAL_MINUTES.matcher(s);
        if (m.matches()) {
            double lat = Integer.parseInt(m.group(2)) + Double.parseDouble(m.group(3)) / 60.0;
            double lon = Integer.parseInt(m.group(5)) + Double.parseDouble(m.group(6)) / 60.0;
            return signed(lat, m.group(1), lon, m.group(4));
        }

        m = DMS_TRAILING.matcher(s);
        if (m.matches()) {
            double lat = dms(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Double.parseDouble(m.group(3)));
            double lon = dms(Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                    Double.parseDouble(m.group(7)));
            return signed(lat, m.group(4), lon, m.group(8));
        }

        m = ICAO_DEGREES_MINUTES.matcher(s);
        if (m.matches()) {
            double lat = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0;
            double lon = Integer.parseInt(m.group(4)) + Integer.parseInt(m.group(5)) / 60.0;
            return signed(lat, m.group(3), lon, m.group(6));
        }

        m = ICAO_WHOLE_DEGREES.matcher(s);
        if (m.matches()) {
            return signed(Integer.parseInt(m.group(1)), m.group(2),
                    Integer.parseInt(m.group(3)), m.group(4));
        }

        m = OCEANIC_SHORTHAND.matcher(s);
        if (m.matches()) {
            return parseOceanicShorthand(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3).charAt(0));
        }

        m = SIGNED_DECIMAL.matcher(s);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1));
            double lon = Double.parseDouble(m.group(2));
            if (lat < -90.0 || lat > 90.0) {
                return null;
            }
            return new GeoPoint(lat, lon);
        }

        return null;
    }

    /**
     * Oceanic shorthand: two digits of latitude, two of longitude, and a letter naming the
     * quadrant — {@code N} north-west, {@code E} north-east, {@code S} south-east,
     * {@code W} south-west. So {@code 5130N} is 51N 030W, the form a NAT track message uses.
     *
     * <p>The notation cannot express a longitude of 100 degrees or more; such a point is
     * written out in one of the longer forms instead.
     */
    static GeoPoint parseOceanicShorthand(int latDegrees, int lonDegrees, char quadrant) {
        double lat = latDegrees;
        double lon = lonDegrees;
        return switch (quadrant) {
            case 'N' -> new GeoPoint(lat, -lon);
            case 'E' -> new GeoPoint(lat, lon);
            case 'S' -> new GeoPoint(-lat, lon);
            case 'W' -> new GeoPoint(-lat, -lon);
            default -> throw new IllegalArgumentException("bad oceanic quadrant: " + quadrant);
        };
    }

    // ------------------------------------------------------------------ rendering

    /** {@code N5130.0W00007.5}, the form printed beside each waypoint on an OFP. */
    public static String toArincDegreesMinutes(GeoPoint p) {
        double absLat = Math.abs(p.latitude());
        double absLon = Math.abs(p.longitude());
        int latDeg = (int) absLat;
        int lonDeg = (int) absLon;
        double latMin = (absLat - latDeg) * 60.0;
        double lonMin = (absLon - lonDeg) * 60.0;
        // Rounding minutes to one decimal can reach 60.0; carry into the degrees.
        if (Math.round(latMin * 10.0) == 600L) {
            latDeg += 1;
            latMin = 0.0;
        }
        if (Math.round(lonMin * 10.0) == 600L) {
            lonDeg += 1;
            lonMin = 0.0;
        }
        return String.format(Locale.ROOT, "%s%02d%04.1f%s%03d%04.1f",
                p.latitude() >= 0 ? "N" : "S", latDeg, latMin,
                p.longitude() >= 0 ? "E" : "W", lonDeg, lonMin);
    }

    /** {@code 513000N 0000730W}, the form AIP tables and NOTAM area definitions use. */
    public static String toDegreesMinutesSeconds(GeoPoint p) {
        return dmsPart(Math.abs(p.latitude()), 2) + (p.latitude() >= 0 ? "N" : "S")
                + " " + dmsPart(Math.abs(p.longitude()), 3) + (p.longitude() >= 0 ? "E" : "W");
    }

    /** {@code 5130N} when the point falls on a whole degree and the quadrant allows it. */
    public static String toOceanicShorthandOrNull(GeoPoint p) {
        double absLat = Math.abs(p.latitude());
        double absLon = Math.abs(p.longitude());
        if (absLat != Math.rint(absLat) || absLon != Math.rint(absLon)) {
            return null;
        }
        if (absLat > 99.0 || absLon > 99.0) {
            return null;
        }
        char quadrant;
        if (p.latitude() >= 0) {
            quadrant = p.longitude() >= 0 ? 'E' : 'N';
        } else {
            quadrant = p.longitude() >= 0 ? 'S' : 'W';
        }
        return String.format(Locale.ROOT, "%02d%02d%c", (int) absLat, (int) absLon, quadrant);
    }

    /** {@code 51.500000/-0.125000}, the lossless internal form. */
    public static String toSignedDecimal(GeoPoint p) {
        return String.format(Locale.ROOT, "%.6f/%.6f", p.latitude(), p.longitude());
    }

    private static String dmsPart(double absDegrees, int degreeDigits) {
        int deg = (int) absDegrees;
        double remainderMinutes = (absDegrees - deg) * 60.0;
        int min = (int) remainderMinutes;
        long sec = Math.round((remainderMinutes - min) * 60.0);
        if (sec == 60L) {
            sec = 0L;
            min += 1;
        }
        if (min == 60) {
            min = 0;
            deg += 1;
        }
        return String.format(Locale.ROOT, "%0" + degreeDigits + "d%02d%02d", deg, min, sec);
    }

    private static double dms(int degrees, int minutes, double seconds) {
        if (minutes >= 60 || seconds >= 60.0) {
            throw new IllegalArgumentException(
                    "minutes/seconds out of range: " + degrees + " " + minutes + " " + seconds);
        }
        return degrees + minutes / 60.0 + seconds / 3600.0;
    }

    private static GeoPoint signed(double latMagnitude, String latHemisphere,
                                   double lonMagnitude, String lonHemisphere) {
        double lat = latHemisphere.equals("S") ? -latMagnitude : latMagnitude;
        double lon = lonHemisphere.equals("W") ? -lonMagnitude : lonMagnitude;
        return new GeoPoint(lat, lon);
    }
}
