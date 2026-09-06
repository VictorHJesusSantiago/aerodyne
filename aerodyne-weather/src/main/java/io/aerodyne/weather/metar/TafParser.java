package io.aerodyne.weather.metar;

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
 * Decodes terminal aerodrome forecasts.
 *
 * <p>A TAF is a header, a body, and a run of change groups. The groups reuse the METAR body
 * syntax exactly — the same wind, visibility, weather and cloud tokens — so the group decoding
 * is shared with {@link MetarParser}; what is specific here is the structure: splitting the
 * forecast at its change markers and working out each group's period.
 *
 * <p>Periods are the fiddly part, because the forecast expresses them in day-of-month and hour
 * with no month, and because a {@code FM} group has no stated end. Days are resolved against
 * the validity period the header states, and each {@code FM} group is closed at the start of
 * the next one — or at the end of the forecast when it is the last.
 */
public final class TafParser {

    private static final Pattern STATION = Pattern.compile("^[A-Z][A-Z0-9]{3}$");
    private static final Pattern ISSUE_TIME = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})Z$");
    /** {@code 0112/0212}: from day 01 at 12:00 to day 02 at 12:00. */
    private static final Pattern VALIDITY = Pattern.compile("^(\\d{2})(\\d{2})/(\\d{2})(\\d{2})$");
    /** {@code FM011230}: an immediate change at 12:30 on day 01. */
    private static final Pattern FROM_GROUP = Pattern.compile("^FM(\\d{2})(\\d{2})(\\d{2})$");
    private static final Pattern PROBABILITY = Pattern.compile("^PROB(\\d{2})$");

    /** Hour 24 means midnight ending the stated day, not an hour of the next one. */
    private static final int END_OF_DAY_HOUR = 24;

    private final Instant reference;

    public TafParser() {
        this(Instant.now());
    }

    public TafParser(Instant reference) {
        this.reference = reference;
    }

    /**
     * Decodes one forecast.
     *
     * @throws MetarParser.MetarParseException when the station or the validity period cannot be
     *                                         read
     */
    public TafReport parse(String forecast) {
        if (forecast == null || forecast.isBlank()) {
            throw new MetarParser.MetarParseException("empty forecast", forecast);
        }
        String cleaned = forecast.trim().replace("=", "").replaceAll("\\s+", " ");
        String[] tokens = cleaned.toUpperCase(Locale.ROOT).split(" ");

        int i = 0;
        if (i < tokens.length && tokens[i].equals("TAF")) {
            i++;
        }
        boolean amended = false;
        boolean corrected = false;
        while (i < tokens.length && (tokens[i].equals("AMD") || tokens[i].equals("COR"))) {
            amended |= tokens[i].equals("AMD");
            corrected |= tokens[i].equals("COR");
            i++;
        }
        if (i >= tokens.length || !STATION.matcher(tokens[i]).matches()) {
            throw new MetarParser.MetarParseException("no station identifier", forecast);
        }
        String station = tokens[i++];

        Instant issuedAt = null;
        if (i < tokens.length) {
            Matcher issue = ISSUE_TIME.matcher(tokens[i]);
            if (issue.matches()) {
                issuedAt = resolveAgainstReference(Integer.parseInt(issue.group(1)),
                        Integer.parseInt(issue.group(2)), Integer.parseInt(issue.group(3)));
                i++;
            }
        }
        // AMD and COR sometimes follow the issue time rather than preceding it.
        while (i < tokens.length && (tokens[i].equals("AMD") || tokens[i].equals("COR"))) {
            amended |= tokens[i].equals("AMD");
            corrected |= tokens[i].equals("COR");
            i++;
        }

        if (i >= tokens.length) {
            throw new MetarParser.MetarParseException("no validity period", forecast);
        }
        Matcher validity = VALIDITY.matcher(tokens[i]);
        if (!validity.matches()) {
            throw new MetarParser.MetarParseException("no validity period", forecast);
        }
        Instant anchor = issuedAt != null ? issuedAt : reference;
        Instant validFrom = resolveDayHour(Integer.parseInt(validity.group(1)),
                Integer.parseInt(validity.group(2)), anchor);
        Instant validTo = resolveDayHour(Integer.parseInt(validity.group(3)),
                Integer.parseInt(validity.group(4)), validFrom);
        if (!validTo.isAfter(validFrom)) {
            // A period that appears to end before it starts has run into the next month.
            validTo = resolveDayHour(Integer.parseInt(validity.group(3)),
                    Integer.parseInt(validity.group(4)), validFrom.plusSeconds(86_400L * 28));
        }
        i++;
        if (issuedAt == null) {
            issuedAt = validFrom;
        }

        if (i < tokens.length && tokens[i].equals("CNL")) {
            return new TafReport(station, issuedAt, validFrom, validTo, List.of(),
                    amended, corrected, true, forecast.trim());
        }

        List<TafGroup> groups = readGroups(
                java.util.Arrays.copyOfRange(tokens, i, tokens.length), validFrom, validTo);
        return new TafReport(station, issuedAt, validFrom, validTo, groups,
                amended, corrected, false, forecast.trim());
    }

    /**
     * Splits the body and change groups apart and decodes each. The body runs until the first
     * change marker; every group after that runs until the next marker.
     */
    private List<TafGroup> readGroups(String[] tokens, Instant validFrom, Instant validTo) {
        List<TafGroup> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        TafChangeType currentType = TafChangeType.INITIAL;
        Integer currentProbability = null;
        Instant currentFrom = validFrom;
        Instant currentTo = validTo;

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            Matcher from = FROM_GROUP.matcher(token);
            Matcher probability = PROBABILITY.matcher(token);

            boolean startsGroup = from.matches() || probability.matches()
                    || token.equals("BECMG") || token.equals("TEMPO");
            if (!startsGroup) {
                current.add(token);
                continue;
            }

            groups.add(buildGroup(currentType, currentProbability, currentFrom, currentTo, current));
            current = new ArrayList<>();
            currentProbability = null;

            if (from.matches()) {
                currentType = TafChangeType.FROM;
                currentFrom = resolveDayHourMinute(Integer.parseInt(from.group(1)),
                        Integer.parseInt(from.group(2)), Integer.parseInt(from.group(3)), validFrom);
                // A FM group runs until the next one; closed below once that is known.
                currentTo = validTo;
                continue;
            }

            if (probability.matches()) {
                currentProbability = Integer.parseInt(probability.group(1));
                currentType = TafChangeType.PROBABILITY;
                // PROB is often followed by TEMPO: "PROB30 TEMPO 0118/0122".
                if (i + 1 < tokens.length && tokens[i + 1].equals("TEMPO")) {
                    i++;
                }
            } else {
                currentType = token.equals("BECMG")
                        ? TafChangeType.BECOMING
                        : TafChangeType.TEMPORARY;
            }

            // BECMG, TEMPO and PROB all state their own period.
            if (i + 1 < tokens.length) {
                Matcher period = VALIDITY.matcher(tokens[i + 1]);
                if (period.matches()) {
                    currentFrom = resolveDayHour(Integer.parseInt(period.group(1)),
                            Integer.parseInt(period.group(2)), validFrom);
                    currentTo = resolveDayHour(Integer.parseInt(period.group(3)),
                            Integer.parseInt(period.group(4)), currentFrom);
                    i++;
                    continue;
                }
            }
            currentFrom = validFrom;
            currentTo = validTo;
        }
        groups.add(buildGroup(currentType, currentProbability, currentFrom, currentTo, current));

        return closeFromGroups(groups, validTo);
    }

    /**
     * A {@code FM} group states no end, so it runs until the next group that replaces the
     * prevailing forecast, or to the end of the TAF.
     */
    private static List<TafGroup> closeFromGroups(List<TafGroup> groups, Instant validTo) {
        List<TafGroup> closed = new ArrayList<>(groups);
        for (int i = 0; i < closed.size(); i++) {
            TafGroup group = closed.get(i);
            if (group.changeType() != TafChangeType.FROM
                    && group.changeType() != TafChangeType.INITIAL) {
                continue;
            }
            Instant end = validTo;
            for (int j = i + 1; j < closed.size(); j++) {
                if (closed.get(j).changeType().replacesPrevailing()) {
                    end = closed.get(j).validFrom();
                    break;
                }
            }
            closed.set(i, group.endingAt(end));
        }
        return List.copyOf(closed);
    }

    private static TafGroup buildGroup(TafChangeType type, Integer probability,
                                       Instant from, Instant to, List<String> tokens) {
        MetarParser.Accumulator state = new MetarParser.Accumulator();
        tokens.forEach(token -> MetarParser.readGroup(token, state));
        return new TafGroup(type, probability, from, to, state.conditions());
    }

    /**
     * Resolves a day-of-month and hour against an anchor instant, choosing the occurrence at or
     * after it. Forecast periods run forward from the header, so unlike a METAR observation the
     * right choice here is the <em>next</em> occurrence rather than the most recent one.
     */
    private static Instant resolveDayHour(int day, int hour, Instant anchor) {
        boolean endOfDay = hour == END_OF_DAY_HOUR;
        LocalDate anchorDate = LocalDate.ofInstant(anchor, ZoneOffset.UTC);
        for (int monthOffset = 0; monthOffset < 2; monthOffset++) {
            LocalDate month = anchorDate.plusMonths(monthOffset);
            if (day > month.lengthOfMonth()) {
                continue;
            }
            LocalDateTime candidate = LocalDateTime.of(
                    month.getYear(), month.getMonth(), day, endOfDay ? 0 : hour, 0);
            Instant instant = endOfDay
                    ? candidate.plusDays(1).toInstant(ZoneOffset.UTC)
                    : candidate.toInstant(ZoneOffset.UTC);
            // Allow an hour of slack so a period starting on the issue hour resolves to itself.
            if (!instant.isBefore(anchor.minusSeconds(3600))) {
                return instant;
            }
        }
        throw new MetarParser.MetarParseException(
                "cannot place day " + day + " hour " + hour + " after " + anchor, "");
    }

    private static Instant resolveDayHourMinute(int day, int hour, int minute, Instant anchor) {
        return resolveDayHour(day, hour, anchor).plusSeconds(minute * 60L);
    }

    private Instant resolveAgainstReference(int day, int hour, int minute) {
        LocalDate referenceDate = LocalDate.ofInstant(reference, ZoneOffset.UTC);
        for (int monthsBack = 0; monthsBack < 2; monthsBack++) {
            LocalDate month = referenceDate.minusMonths(monthsBack);
            if (day > month.lengthOfMonth()) {
                continue;
            }
            Instant instant = LocalDateTime.of(month.getYear(), month.getMonth(), day, hour, minute)
                    .toInstant(ZoneOffset.UTC);
            if (!instant.isAfter(reference.plusSeconds(3600))) {
                return instant;
            }
        }
        throw new MetarParser.MetarParseException(
                "issue day " + day + " does not fall in the last two months", "");
    }
}
