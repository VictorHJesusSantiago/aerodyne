package io.aerodyne.navdata.procedure;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * The altitude a procedure leg must be flown at, or above, or below, or between.
 *
 * <p>The shape of the constraint is carried by a one-character description in the record, and
 * the two altitude fields mean different things depending on it. That is the trap: reading
 * {@code altitudeOne} as "the altitude" is right for an at-or-above constraint and wrong for a
 * window, where it is the top and {@code altitudeTwo} is the bottom. So the description is
 * decoded into a {@link Kind} and the accessors are named for what they mean rather than for
 * which field they came from.
 *
 * @param kind         what sort of constraint it is
 * @param altitudeOne  the first altitude field, in feet; {@code null} when unpublished
 * @param altitudeTwo  the second altitude field, in feet; {@code null} when unpublished
 */
public record AltitudeConstraint(Kind kind, Integer altitudeOne, Integer altitudeTwo) {

    /** No constraint at all. */
    public static final AltitudeConstraint NONE = new AltitudeConstraint(Kind.NONE, null, null);

    /** What the altitude fields mean. */
    public enum Kind {
        /** No altitude is published for the leg. */
        NONE,
        /** Cross at this altitude. */
        AT,
        /** Cross at or above this altitude. */
        AT_OR_ABOVE,
        /** Cross at or below this altitude. */
        AT_OR_BELOW,
        /** Cross at or below the first and at or above the second: an altitude window. */
        BETWEEN,
        /** Cross at or above the second altitude, having been at the first. */
        AT_OR_ABOVE_SECOND,
        /** The leg is flown on a glide path, with the altitude as a crossing height. */
        GLIDE_PATH
    }

    /**
     * Reads the altitude description character and the two altitude fields.
     *
     * <p>Codes follow ARINC 424: blank is "at", {@code +} at or above, {@code -} at or below,
     * {@code B} a window, {@code C} at or above the second altitude, and {@code G}, {@code H},
     * {@code I}, {@code J}, {@code V} the glide-path variants.
     */
    public static AltitudeConstraint of(String description, Integer altitudeOne, Integer altitudeTwo) {
        if (altitudeOne == null && altitudeTwo == null) {
            return NONE;
        }
        char code = description == null || description.isBlank()
                ? ' '
                : Character.toUpperCase(description.trim().charAt(0));
        Kind kind = switch (code) {
            case '+' -> Kind.AT_OR_ABOVE;
            case '-' -> Kind.AT_OR_BELOW;
            case 'B' -> Kind.BETWEEN;
            case 'C' -> Kind.AT_OR_ABOVE_SECOND;
            case 'G', 'H', 'I', 'J', 'V' -> Kind.GLIDE_PATH;
            default -> Kind.AT;
        };
        return new AltitudeConstraint(kind, altitudeOne, altitudeTwo);
    }

    /** True when the leg carries any altitude requirement. */
    public boolean isConstrained() {
        return kind != Kind.NONE;
    }

    /** The lowest altitude the constraint permits, when it has a floor. */
    public OptionalInt lowerLimitFt() {
        return switch (kind) {
            case AT, AT_OR_ABOVE, GLIDE_PATH -> optional(altitudeOne);
            case BETWEEN, AT_OR_ABOVE_SECOND -> optional(altitudeTwo);
            case AT_OR_BELOW, NONE -> OptionalInt.empty();
        };
    }

    /** The highest altitude the constraint permits, when it has a ceiling. */
    public OptionalInt upperLimitFt() {
        return switch (kind) {
            case AT, AT_OR_BELOW, BETWEEN, GLIDE_PATH -> optional(altitudeOne);
            case AT_OR_ABOVE, AT_OR_ABOVE_SECOND, NONE -> OptionalInt.empty();
        };
    }

    /** True when an altitude satisfies the constraint. */
    public boolean permits(double altitudeFt) {
        OptionalInt lower = lowerLimitFt();
        if (lower.isPresent() && altitudeFt < lower.getAsInt()) {
            return false;
        }
        OptionalInt upper = upperLimitFt();
        return upper.isEmpty() || altitudeFt <= upper.getAsInt();
    }

    /** How a chart writes it: {@code 5000A}, {@code 3000B}, {@code 4000A6000B}. */
    public String toChartString() {
        return switch (kind) {
            case NONE -> "";
            case AT, GLIDE_PATH -> String.valueOf(altitudeOne);
            case AT_OR_ABOVE -> altitudeOne + "A";
            case AT_OR_BELOW -> altitudeOne + "B";
            case BETWEEN -> altitudeTwo + "A" + altitudeOne + "B";
            case AT_OR_ABOVE_SECOND -> altitudeTwo + "A";
        };
    }

    private static OptionalInt optional(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    @Override
    public String toString() {
        return kind == Kind.NONE ? "unconstrained" : toChartString();
    }

    /**
     * Reads an ARINC altitude field, which may be plain feet or a flight level written
     * {@code FL350}. An unpublished field is {@code null} rather than zero: a leg with no
     * altitude constraint and a leg constrained to sea level are not the same thing.
     */
    public static Integer parseAltitudeField(String field) {
        if (field == null) {
            return null;
        }
        String cleaned = field.trim().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.startsWith("UNL") || cleaned.chars().allMatch(c -> c == '-')) {
            return null;
        }
        try {
            if (cleaned.startsWith("FL")) {
                return Integer.parseInt(cleaned.substring(2).trim()) * 100;
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException notAnAltitude) {
            return null;
        }
    }
}
