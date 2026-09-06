package io.aerodyne.navdata.procedure;

import java.util.OptionalInt;

/**
 * A speed restriction on a procedure leg, in knots indicated.
 *
 * <p>Almost every published restriction is a maximum — "220 kt or less" on a departure to keep
 * the aircraft inside the protected area — but the format allows a minimum and a mandatory
 * speed too, and reading a minimum as a maximum would have the aircraft slow down where it was
 * told to speed up.
 *
 * @param kind    what sort of restriction it is
 * @param speedKt the speed, in knots indicated
 */
public record SpeedConstraint(Kind kind, int speedKt) {

    /** No restriction. */
    public static final SpeedConstraint NONE = new SpeedConstraint(Kind.NONE, 0);

    /** What the speed means. */
    public enum Kind {
        /** No speed is published for the leg. */
        NONE,
        /** At or below this speed — the usual case. */
        MAXIMUM,
        /** At or above this speed. */
        MINIMUM,
        /** At this speed. */
        MANDATORY
    }

    /**
     * Reads the speed limit and its description. A blank description means a maximum, which is
     * what the great majority of published restrictions are; {@code +} is a minimum and
     * {@code @} a mandatory speed.
     */
    public static SpeedConstraint of(String description, Integer speedKt) {
        if (speedKt == null || speedKt <= 0) {
            return NONE;
        }
        char code = description == null || description.isBlank()
                ? ' '
                : description.trim().charAt(0);
        Kind kind = switch (code) {
            case '+' -> Kind.MINIMUM;
            case '@' -> Kind.MANDATORY;
            default -> Kind.MAXIMUM;
        };
        return new SpeedConstraint(kind, speedKt);
    }

    public boolean isConstrained() {
        return kind != Kind.NONE;
    }

    public OptionalInt maximumKt() {
        return kind == Kind.MAXIMUM || kind == Kind.MANDATORY
                ? OptionalInt.of(speedKt)
                : OptionalInt.empty();
    }

    public OptionalInt minimumKt() {
        return kind == Kind.MINIMUM || kind == Kind.MANDATORY
                ? OptionalInt.of(speedKt)
                : OptionalInt.empty();
    }

    /** True when a speed satisfies the restriction. */
    public boolean permits(double indicatedAirspeedKt) {
        return switch (kind) {
            case NONE -> true;
            case MAXIMUM -> indicatedAirspeedKt <= speedKt;
            case MINIMUM -> indicatedAirspeedKt >= speedKt;
            case MANDATORY -> Math.abs(indicatedAirspeedKt - speedKt) < 1.0;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case NONE -> "unrestricted";
            case MAXIMUM -> speedKt + "K max";
            case MINIMUM -> speedKt + "K min";
            case MANDATORY -> speedKt + "K";
        };
    }
}
