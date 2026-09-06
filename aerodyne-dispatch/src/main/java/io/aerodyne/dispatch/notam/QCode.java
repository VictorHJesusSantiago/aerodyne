package io.aerodyne.dispatch.notam;

import java.util.Locale;

/**
 * The five-letter code in a NOTAM's Q line, which says what the NOTAM is about without anyone
 * having to read its free text.
 *
 * <p>The letters are {@code Q} followed by a two-letter <em>subject</em> and a two-letter
 * <em>condition</em>: {@code QMRLC} is {@code MR} runway, {@code LC} closed. That structure is
 * what makes a NOTAM machine-readable at all, and it is why dispatch screens on the Q code and
 * reads the E line only to show a human.
 *
 * <p>The tables run to hundreds of entries. What is decoded here is the handful that change a
 * dispatch decision — whether a runway, an approach aid or the whole aerodrome is unavailable —
 * and everything else is carried through as its raw letters.
 *
 * @param raw       the five characters as published
 * @param subject   the two-letter subject, e.g. {@code MR}
 * @param condition the two-letter condition, e.g. {@code LC}
 */
public record QCode(String raw, String subject, String condition) {

    /** The code used when a NOTAM has no readable Q line. */
    public static final QCode UNKNOWN = new QCode("QXXXX", "XX", "XX");

    /**
     * Parses a five-letter Q code. Returns {@link #UNKNOWN} rather than throwing: a NOTAM with a
     * malformed Q line is still a NOTAM, and dropping it because a code was mistyped would hide
     * a real closure.
     */
    public static QCode parse(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        String cleaned = code.trim().toUpperCase(Locale.ROOT);
        if (cleaned.length() != 5 || cleaned.charAt(0) != 'Q') {
            return UNKNOWN;
        }
        return new QCode(cleaned, cleaned.substring(1, 3), cleaned.substring(3, 5));
    }

    /** True when the subject is a runway. */
    public boolean concernsRunway() {
        return subject.startsWith("MR") || subject.equals("MR");
    }

    /** True when the subject is the aerodrome as a whole. */
    public boolean concernsAerodrome() {
        return subject.startsWith("FA") || subject.equals("FA");
    }

    /** True when the subject is a navigation aid or an instrument approach. */
    public boolean concernsNavaid() {
        return subject.startsWith("N") || subject.startsWith("I");
    }

    /** True when the subject is airspace: a restricted area, a danger area, a route. */
    public boolean concernsAirspace() {
        return subject.startsWith("R") || subject.startsWith("A");
    }

    /**
     * True when the condition says the thing is unavailable.
     *
     * <p>{@code LC} closed, {@code AS} unserviceable, {@code AW} withdrawn, {@code LT} limited
     * to a degree that amounts to closure, {@code CC} completed but not usable.
     */
    public boolean isUnavailable() {
        return switch (condition) {
            case "LC", "AS", "AW", "LT", "AU", "CD" -> true;
            default -> false;
        };
    }

    /** True when the condition is purely informational and changes nothing operationally. */
    public boolean isInformational() {
        return switch (condition) {
            case "XX", "TT", "CN", "CS" -> true;
            default -> false;
        };
    }

    /** A short description, for a briefing package. */
    public String describe() {
        String what = concernsRunway() ? "runway"
                : concernsAerodrome() ? "aerodrome"
                : concernsNavaid() ? "navigation aid"
                : concernsAirspace() ? "airspace"
                : "subject " + subject;
        String state = isUnavailable() ? "unavailable"
                : isInformational() ? "information"
                : "condition " + condition;
        return what + " " + state;
    }

    @Override
    public String toString() {
        return raw;
    }
}
