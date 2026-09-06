package io.aerodyne.navdata;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * An AIRAC cycle: the 28-day window over which a set of navigation data is valid.
 *
 * <p>Cycles are numbered {@code YYNN} — the last two digits of the year and the ordinal of the
 * cycle within it, so {@code 2413} is the thirteenth cycle of 2024. They all begin on a
 * Thursday, counted from a fixed epoch, and a flight plan is only legal against the cycle
 * effective on its date of flight.
 */
public record AiracCycle(int year, int ordinal) implements Comparable<AiracCycle> {

    /** The start of AIRAC cycle 0101; every other cycle boundary is a multiple of 28 days from here. */
    private static final LocalDate EPOCH = LocalDate.of(2001, 1, 25);
    private static final int CYCLE_DAYS = 28;

    public AiracCycle {
        if (year < 1964 || year > 2099) {
            throw new IllegalArgumentException("AIRAC year out of supported range: " + year);
        }
        if (ordinal < 1 || ordinal > 14) {
            throw new IllegalArgumentException("AIRAC ordinal out of range: " + ordinal);
        }
    }

    /** Parses the four-digit {@code YYNN} form printed on the navdata media and on the OFP. */
    public static AiracCycle parse(String yyNN) {
        if (yyNN == null || !yyNN.matches("\\d{4}")) {
            throw new IllegalArgumentException("AIRAC cycle must be four digits: '" + yyNN + "'");
        }
        int yy = Integer.parseInt(yyNN.substring(0, 2));
        int nn = Integer.parseInt(yyNN.substring(2, 4));
        // Two-digit years are read as 2000-2099; no cycle before 2001 is expressible here anyway.
        return new AiracCycle(2000 + yy, nn);
    }

    /** The cycle in effect on a given date. */
    public static AiracCycle effectiveOn(LocalDate date) {
        if (date.isBefore(EPOCH)) {
            throw new IllegalArgumentException("date precedes the AIRAC epoch " + EPOCH + ": " + date);
        }
        long cyclesSinceEpoch = ChronoUnit.DAYS.between(EPOCH, date) / CYCLE_DAYS;
        LocalDate start = EPOCH.plusDays(cyclesSinceEpoch * CYCLE_DAYS);
        // The ordinal is the count of cycle starts that fall in the same calendar year.
        LocalDate firstOfYear = firstCycleStartOfYear(start.getYear());
        int ordinal = (int) (ChronoUnit.DAYS.between(firstOfYear, start) / CYCLE_DAYS) + 1;
        return new AiracCycle(start.getYear(), ordinal);
    }

    public static AiracCycle effectiveNow() {
        return effectiveOn(LocalDate.now(ZoneOffset.UTC));
    }

    /** First day of the cycle, a Thursday, at 00:00 UTC. */
    public LocalDate effectiveDate() {
        return firstCycleStartOfYear(year).plusDays((long) (ordinal - 1) * CYCLE_DAYS);
    }

    /** First day on which this cycle is no longer valid — the next cycle's effective date. */
    public LocalDate expiryDate() {
        return effectiveDate().plusDays(CYCLE_DAYS);
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(effectiveDate()) && date.isBefore(expiryDate());
    }

    public boolean covers(Instant instant) {
        return covers(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    /** How long this cycle still has to run at {@code date}; negative once it has expired. */
    public Duration remainingAt(LocalDate date) {
        return Duration.ofDays(ChronoUnit.DAYS.between(date, expiryDate()));
    }

    public AiracCycle next() {
        return effectiveOn(expiryDate());
    }

    public AiracCycle previous() {
        return effectiveOn(effectiveDate().minusDays(1));
    }

    /** The {@code YYNN} identifier. */
    public String identifier() {
        return String.format("%02d%02d", year % 100, ordinal);
    }

    @Override
    public int compareTo(AiracCycle other) {
        return effectiveDate().compareTo(other.effectiveDate());
    }

    @Override
    public String toString() {
        return identifier();
    }

    private static LocalDate firstCycleStartOfYear(int year) {
        LocalDate janFirst = LocalDate.of(year, 1, 1);
        long daysSinceEpoch = ChronoUnit.DAYS.between(EPOCH, janFirst);
        long cycles = Math.floorDiv(daysSinceEpoch, CYCLE_DAYS);
        LocalDate candidate = EPOCH.plusDays(cycles * CYCLE_DAYS);
        // floorDiv can land on the last cycle of the previous year; step forward if so.
        return candidate.isBefore(janFirst) ? candidate.plusDays(CYCLE_DAYS) : candidate;
    }
}
