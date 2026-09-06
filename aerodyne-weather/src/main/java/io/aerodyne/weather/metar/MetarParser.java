package io.aerodyne.weather.metar;

import io.aerodyne.geo.Units;
import io.aerodyne.weather.WindVector;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes METAR and SPECI reports.
 *
 * <p>The format is a sequence of self-identifying groups in a fixed order, and this parser
 * walks the tokens once, offering each to the group recognisers in that order. A token that
 * nothing recognises is skipped rather than treated as an error: METAR is issued by every state
 * in the world and the long tail of national additions is endless, so a parser that rejected
 * anything unfamiliar would reject a large fraction of real traffic. What it will not do is
 * <em>guess</em> — an unrecognised group leaves the corresponding field unreported, and
 * {@link SurfaceConditions#meets} treats unreported as not meeting a minimum.
 *
 * <p>Two things need context the report does not carry. The observation time is a day, hour and
 * minute with no month, so it is resolved against a reference instant — usually now — by taking
 * the most recent occurrence of that day-of-month. And the visibility can be metres or statute
 * miles; the unit is inferred from the {@code SM} suffix, and everything is stored in metres.
 */
public final class MetarParser {

    private static final Pattern STATION = Pattern.compile("^[A-Z][A-Z0-9]{3}$");
    private static final Pattern OBSERVATION_TIME = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})Z$");
    private static final Pattern WIND = Pattern.compile(
            "^(\\d{3}|VRB|///)(\\d{2,3}|//)(?:G(\\d{2,3}))?(KT|MPS|KMH)$");
    private static final Pattern WIND_VARIATION = Pattern.compile("^(\\d{3})V(\\d{3})$");
    private static final Pattern VISIBILITY_METRES = Pattern.compile("^(\\d{4})(NDV)?$");
    private static final Pattern VISIBILITY_MILES = Pattern.compile(
            "^([MP])?(\\d{1,2})(?:/(\\d))?SM$");
    private static final Pattern VISIBILITY_MILES_FRACTION = Pattern.compile("^(\\d)/(\\d)SM$");
    private static final Pattern RVR = Pattern.compile(
            "^R(\\w{2,3})/([MP])?(\\d{4})(?:V([MP])?(\\d{4}))?([UDN])?$");
    private static final Pattern CLOUD = Pattern.compile(
            "^(FEW|SCT|BKN|OVC)(\\d{3}|///)(CB|TCU)?$");
    private static final Pattern VERTICAL_VISIBILITY = Pattern.compile("^VV(\\d{3}|///)$");
    private static final Pattern TEMPERATURE = Pattern.compile("^(M?\\d{2})/(M?\\d{2})?$");
    private static final Pattern QNH_HPA = Pattern.compile("^Q(\\d{4}|////)$");
    private static final Pattern QNH_INHG = Pattern.compile("^A(\\d{4}|////)$");

    /**
     * A present-weather group: an optional intensity or vicinity marker, then any number of
     * descriptors and phenomena from the WMO tables. Both halves are optional individually —
     * {@code VCSH} is descriptor-only and {@code BR} is phenomenon-only — but a group with
     * neither is not weather, which the caller checks for.
     */
    private static final Pattern PRESENT_WEATHER = Pattern.compile(
            "^([-+]|VC)?((?:MI|BC|PR|DR|BL|SH|TS|FZ)*)"
                    + "((?:DZ|RA|SN|SG|IC|PL|GR|GS|UP|BR|FG|FU|VA|DU|SA|HZ|PY|PO|SQ|FC|SS|DS)*)$");

    /** The groups that begin a trend forecast; everything from one of these on is the trend. */
    private static final List<String> TREND_MARKERS = List.of("NOSIG", "BECMG", "TEMPO");

    private final Instant reference;

    /** A parser that resolves observation days against the system clock. */
    public MetarParser() {
        this(Instant.now());
    }

    /**
     * A parser that resolves observation days against a fixed instant, which is what makes the
     * date arithmetic testable.
     */
    public MetarParser(Instant reference) {
        this.reference = reference;
    }

    /**
     * Decodes one report.
     *
     * @throws MetarParseException when the station or observation time cannot be read; without
     *                             those two the report cannot be attributed to an aerodrome or a
     *                             time, and a weather report that might belong to anywhere is
     *                             worse than none
     */
    public MetarReport parse(String report) {
        if (report == null || report.isBlank()) {
            throw new MetarParseException("empty report", report);
        }
        String cleaned = report.trim().replace("=", "");
        String remarks = null;
        int remarkIndex = cleaned.indexOf(" RMK ");
        if (remarkIndex >= 0) {
            remarks = cleaned.substring(remarkIndex + 5).trim();
            cleaned = cleaned.substring(0, remarkIndex);
        }

        String[] tokens = cleaned.toUpperCase(Locale.ROOT).split("\\s+");
        Accumulator state = new Accumulator();
        state.raw = report.trim();

        int i = 0;
        // A bulletin may or may not lead with the report type.
        if (i < tokens.length && (tokens[i].equals("METAR") || tokens[i].equals("SPECI"))) {
            state.speci = tokens[i].equals("SPECI");
            i++;
        }
        if (i < tokens.length && tokens[i].equals("COR")) {
            state.corrected = true;
            i++;
        }
        if (i >= tokens.length || !STATION.matcher(tokens[i]).matches()) {
            throw new MetarParseException("no station identifier", report);
        }
        state.station = tokens[i++];

        if (i >= tokens.length) {
            throw new MetarParseException("no observation time", report);
        }
        Matcher time = OBSERVATION_TIME.matcher(tokens[i]);
        if (!time.matches()) {
            throw new MetarParseException("no observation time", report);
        }
        state.observedAt = resolveObservationTime(
                Integer.parseInt(time.group(1)),
                Integer.parseInt(time.group(2)),
                Integer.parseInt(time.group(3)),
                report);
        i++;

        for (; i < tokens.length; i++) {
            String token = tokens[i];
            if (TREND_MARKERS.contains(token)) {
                state.trend = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length));
                break;
            }
            if (token.equals("AUTO")) {
                state.automatic = true;
                continue;
            }
            if (token.equals("COR")) {
                state.corrected = true;
                continue;
            }
            // A whole-mile visibility can be followed by a fraction: "1 1/2SM".
            if (token.matches("\\d{1,2}") && i + 1 < tokens.length
                    && VISIBILITY_MILES_FRACTION.matcher(tokens[i + 1]).matches()) {
                Matcher fraction = VISIBILITY_MILES_FRACTION.matcher(tokens[i + 1]);
                if (fraction.matches()) {
                    double miles = Integer.parseInt(token)
                            + (double) Integer.parseInt(fraction.group(1)) / Integer.parseInt(fraction.group(2));
                    state.visibilityMetres = statuteMilesToMetres(miles);
                    i++;
                    continue;
                }
            }
            readGroup(token, state);
        }

        return new MetarReport(state.station, state.observedAt, state.conditions(),
                state.temperatureC, state.dewPointC, state.qnhHpa, state.speci, state.automatic,
                state.corrected, state.rvr, state.trend, remarks, state.raw);
    }

    /**
     * Decodes one group of a report body. Shared with the TAF parser, whose forecast groups
     * carry the same wind, visibility, weather and cloud groups as an observation.
     */
    static void readGroup(String token, Accumulator state) {
        if (token.equals("CAVOK")) {
            state.cavok = true;
            return;
        }
        if (token.equals("NSW")) {
            // "No significant weather": an explicit statement that the weather has cleared.
            state.presentWeather.clear();
            return;
        }
        if (token.equals("////") || token.equals("/////")) {
            return;
        }

        Matcher m = WIND.matcher(token);
        if (m.matches()) {
            readWind(m, state);
            return;
        }
        m = WIND_VARIATION.matcher(token);
        if (m.matches()) {
            state.windVariesFromDeg = Integer.parseInt(m.group(1));
            state.windVariesToDeg = Integer.parseInt(m.group(2));
            return;
        }
        m = RVR.matcher(token);
        if (m.matches()) {
            state.rvr.add(new MetarReport.RunwayVisualRange(m.group(1),
                    Integer.parseInt(m.group(3)),
                    m.group(5) == null ? null : Integer.parseInt(m.group(5)),
                    m.group(6)));
            return;
        }
        m = VISIBILITY_METRES.matcher(token);
        if (m.matches()) {
            int metres = Integer.parseInt(m.group(1));
            // 9999 is the code for "ten kilometres or more", not a visibility of 9999 m.
            state.visibilityMetres = metres == 9999 ? SurfaceConditions.CAVOK_VISIBILITY_METRES : metres;
            return;
        }
        m = VISIBILITY_MILES.matcher(token);
        if (m.matches()) {
            double miles = m.group(3) == null
                    ? Integer.parseInt(m.group(2))
                    : (double) Integer.parseInt(m.group(2)) / Integer.parseInt(m.group(3));
            state.visibilityMetres = statuteMilesToMetres(miles);
            return;
        }
        m = CLOUD.matcher(token);
        if (m.matches()) {
            state.clouds.add(new CloudLayer(CloudAmount.fromCode(m.group(1)),
                    m.group(2).equals("///") ? null : Integer.parseInt(m.group(2)) * 100,
                    m.group(3)));
            return;
        }
        m = VERTICAL_VISIBILITY.matcher(token);
        if (m.matches()) {
            state.clouds.add(new CloudLayer(CloudAmount.VERTICAL_VISIBILITY,
                    m.group(1).equals("///") ? null : Integer.parseInt(m.group(1)) * 100, null));
            return;
        }
        if (CloudAmount.fromCode(token) == CloudAmount.NONE) {
            state.clouds.add(CloudLayer.NONE);
            return;
        }
        m = TEMPERATURE.matcher(token);
        if (m.matches()) {
            state.temperatureC = signedTemperature(m.group(1));
            state.dewPointC = m.group(2) == null ? null : signedTemperature(m.group(2));
            return;
        }
        m = QNH_HPA.matcher(token);
        if (m.matches()) {
            state.qnhHpa = m.group(1).startsWith("/") ? null : (double) Integer.parseInt(m.group(1));
            return;
        }
        m = QNH_INHG.matcher(token);
        if (m.matches()) {
            // An American altimeter setting is hundredths of an inch: A2992 is 29.92 inHg.
            state.qnhHpa = m.group(1).startsWith("/")
                    ? null
                    : Units.inchesHgToHectopascal(Integer.parseInt(m.group(1)) / 100.0);
            return;
        }
        m = PRESENT_WEATHER.matcher(token);
        if (m.matches() && !(m.group(2).isEmpty() && m.group(3).isEmpty())) {
            state.presentWeather.add(token);
        }
        // Anything else is a national addition this parser does not read.
    }

    private static void readWind(Matcher m, Accumulator state) {
        String direction = m.group(1);
        String speed = m.group(2);
        if (speed.equals("//")) {
            return;
        }
        double speedKt = convertSpeed(Integer.parseInt(speed), m.group(4));
        // Both VRB and an unmeasured direction leave the speed meaningful and the bearing not.
        boolean directionUnknown = direction.equals("VRB") || direction.equals("///");
        state.variableWind = directionUnknown;
        state.wind = directionUnknown
                ? new WindVector(0.0, speedKt)
                : new WindVector(Integer.parseInt(direction), speedKt);
        state.gustKt = m.group(3) == null
                ? null
                : (int) Math.round(convertSpeed(Integer.parseInt(m.group(3)), m.group(4)));
    }

    /** Most of the world reports knots; a few states report metres or kilometres per hour. */
    private static double convertSpeed(int value, String unit) {
        return switch (unit) {
            case "MPS" -> Units.mpsToKnots(value);
            case "KMH" -> Units.mpsToKnots(value * 1000.0 / 3600.0);
            default -> value;
        };
    }

    private static int statuteMilesToMetres(double miles) {
        return (int) Math.round(miles * Units.METRES_PER_STATUTE_MILE);
    }

    private static int signedTemperature(String group) {
        return group.startsWith("M")
                ? -Integer.parseInt(group.substring(1))
                : Integer.parseInt(group);
    }

    /**
     * Resolves a day-of-month, hour and minute against the reference instant by taking the most
     * recent occurrence of that day. A report timed on the 31st read on the 1st therefore
     * belongs to last month, which is the case that a naive "same month" reading gets wrong.
     */
    private Instant resolveObservationTime(int day, int hour, int minute, String report) {
        if (day < 1 || day > 31 || hour > 23 || minute > 59) {
            throw new MetarParseException(
                    "observation time " + day + "/" + hour + ":" + minute + " is not a time", report);
        }
        LocalDate referenceDate = LocalDate.ofInstant(reference, ZoneOffset.UTC);
        for (int monthsBack = 0; monthsBack < 2; monthsBack++) {
            LocalDate month = referenceDate.minusMonths(monthsBack);
            if (day > month.lengthOfMonth()) {
                continue;
            }
            LocalDateTime candidate = LocalDateTime.of(
                    month.getYear(), month.getMonth(), day, hour, minute);
            Instant instant = candidate.toInstant(ZoneOffset.UTC);
            // A report may be a few minutes ahead of the clock; anything more is last month's.
            if (!instant.isAfter(reference.plusSeconds(3600))) {
                return instant;
            }
        }
        throw new MetarParseException(
                "observation day " + day + " does not fall in the last two months", report);
    }

    /** The fields a report fills in as its groups are read. */
    static final class Accumulator {

        String station;
        Instant observedAt;
        String raw;
        WindVector wind = WindVector.CALM;
        boolean variableWind;
        Integer gustKt;
        Integer windVariesFromDeg;
        Integer windVariesToDeg;
        Integer visibilityMetres;
        boolean cavok;
        final List<CloudLayer> clouds = new ArrayList<>();
        final List<String> presentWeather = new ArrayList<>();
        final List<MetarReport.RunwayVisualRange> rvr = new ArrayList<>();
        Integer temperatureC;
        Integer dewPointC;
        Double qnhHpa;
        boolean speci;
        boolean automatic;
        boolean corrected;
        String trend;

        SurfaceConditions conditions() {
            return new SurfaceConditions(wind, variableWind, gustKt, windVariesFromDeg,
                    windVariesToDeg, visibilityMetres, cavok, clouds, presentWeather);
        }
    }

    /** Raised when a report cannot be attributed to a station and a time. */
    public static class MetarParseException extends RuntimeException {

        private final String report;

        public MetarParseException(String message, String report) {
            super(message + ": '" + report + "'");
            this.report = report;
        }

        public String report() {
            return report;
        }
    }
}
