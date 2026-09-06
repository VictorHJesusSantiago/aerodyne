package io.aerodyne.navdata.arinc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells you whether {@link Arinc424Layout} fits the file you just read.
 *
 * <p>The column map is taken from ARINC 424-18, and suppliers vary. A layout that is wrong for a
 * particular file is the most dangerous failure this parser has, because it does not look like a
 * failure: a position field read four columns to the left still parses, and yields an aerodrome
 * in the wrong country rather than an error. Nothing downstream can tell.
 *
 * <p>What makes it detectable is that a shifted layout does not fail <em>quietly</em> at scale.
 * If the position field is misaligned, nearly every record of that type fails to parse a
 * position, not one in a thousand. So the signal is the <em>rate</em>: a record type where a few
 * records were dropped is a normal file with some bad rows, and one where most were dropped is a
 * layout that does not fit.
 *
 * <p>This turns a silent, systematic error into a loud one, which is the most that can be done
 * without the specification in hand. It does not prove the layout is right — a field shifted
 * within a run of digits would still read and still be wrong — so it reduces the risk rather
 * than removing it. Checking a sample of records against a published chart is what removes it.
 */
public final class Arinc424Diagnostic {

    /**
     * The failure rate above which a record type is called a probable layout mismatch. A real
     * navdata file has a handful of bad records in tens of thousands; a quarter of them failing
     * is not bad data.
     */
    public static final double MISMATCH_THRESHOLD = 0.25;

    /** Below this many records of a type, the rate says nothing useful. */
    public static final int MINIMUM_SAMPLE = 20;

    private Arinc424Diagnostic() {
    }

    /**
     * How one record type fared.
     *
     * @param type      the record type
     * @param read      how many records of it the file held
     * @param failed    how many of those were dropped with an error
     * @param verdict   what the rate suggests
     */
    public record TypeReport(ArincRecordType type, int read, int failed, Verdict verdict) {

        /** The fraction of records of this type that were dropped. */
        public double failureRate() {
            return read == 0 ? 0.0 : (double) failed / read;
        }

        public String describe() {
            return String.format(Locale.ROOT, "%-8s %6d read, %5d dropped (%5.1f%%)  %s",
                    type.code(), read, failed, failureRate() * 100.0, verdict.describe());
        }
    }

    /** What a failure rate suggests about the layout. */
    public enum Verdict {
        /** The records read cleanly. */
        GOOD,
        /** Some records were dropped, at a rate a real file explains. */
        SOME_BAD_RECORDS,
        /** Most records were dropped: the column map probably does not fit this supplier. */
        PROBABLE_LAYOUT_MISMATCH,
        /** Too few records of this type to say anything. */
        TOO_FEW_TO_JUDGE;

        String describe() {
            return switch (this) {
                case GOOD -> "ok";
                case SOME_BAD_RECORDS -> "some bad records";
                case PROBABLE_LAYOUT_MISMATCH -> "PROBABLE LAYOUT MISMATCH";
                case TOO_FEW_TO_JUDGE -> "too few to judge";
            };
        }
    }

    /** The whole report. */
    public record Report(List<TypeReport> types) {

        public Report {
            types = List.copyOf(types);
        }

        /** The record types whose failure rate suggests the layout does not fit. */
        public List<ArincRecordType> suspectTypes() {
            return types.stream()
                    .filter(report -> report.verdict() == Verdict.PROBABLE_LAYOUT_MISMATCH)
                    .map(TypeReport::type)
                    .toList();
        }

        /** True when nothing in the file suggests a layout problem. */
        public boolean layoutLooksRight() {
            return suspectTypes().isEmpty();
        }

        /** The report as lines, for a load log. */
        public String describe() {
            StringBuilder described = new StringBuilder("ARINC 424 layout check\n");
            types.forEach(report -> described.append("  ").append(report.describe()).append('\n'));
            described.append(layoutLooksRight()
                    ? "  the column map fits this file\n"
                    : "  the column map does NOT fit: check "
                    + suspectTypes().stream().map(ArincRecordType::code).toList() + "\n");
            return described.toString();
        }
    }

    /**
     * Judges a parsed file.
     *
     * <p>Counts errors against the records that produced them, by type. Warnings are left out:
     * a warning means the record was kept, and a layout that is merely odd is not the failure
     * being looked for.
     */
    public static Report check(Arinc424File file) {
        Map<ArincRecordType, Integer> failures = new EnumMap<>(ArincRecordType.class);
        for (ArincParseIssue issue : file.issues()) {
            if (issue.isError()) {
                failures.merge(issue.recordType(), 1, Integer::sum);
            }
        }

        List<TypeReport> reports = new ArrayList<>();
        for (ArincRecordType type : ArincRecordType.values()) {
            int read = file.recordCount(type);
            if (read == 0) {
                continue;
            }
            // Unsupported records are not read, so they cannot say anything about the layout.
            if (type == ArincRecordType.UNSUPPORTED) {
                continue;
            }
            int failed = failures.getOrDefault(type, 0);
            reports.add(new TypeReport(type, read, failed, verdictFor(read, failed)));
        }
        return new Report(reports);
    }

    private static Verdict verdictFor(int read, int failed) {
        if (failed == 0) {
            return Verdict.GOOD;
        }
        if (read < MINIMUM_SAMPLE) {
            return Verdict.TOO_FEW_TO_JUDGE;
        }
        return (double) failed / read >= MISMATCH_THRESHOLD
                ? Verdict.PROBABLE_LAYOUT_MISMATCH
                : Verdict.SOME_BAD_RECORDS;
    }
}
