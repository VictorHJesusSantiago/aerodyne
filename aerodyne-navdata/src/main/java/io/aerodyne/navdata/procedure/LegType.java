package io.aerodyne.navdata.procedure;

import java.util.Locale;

/**
 * The ARINC 424 path-and-terminator: how a procedure leg is flown and what ends it.
 *
 * <p>This is the idea that makes coded procedures work at all. A leg is not "go to this point";
 * it is a <em>path</em> — a course, a heading, an arc, a direct track — paired with a
 * <em>terminator</em> that says when the leg is over: reaching a fix, crossing an altitude,
 * intercepting the next leg, or a manual instruction from the crew. The two-letter code names
 * both halves, path first: {@code CF} is a course to a fix, {@code VA} a heading to an altitude,
 * {@code FM} a course from a fix until the crew says otherwise.
 *
 * <p>The distinction the rest of the engine cares about is whether a leg ends somewhere that can
 * be put on a map. A {@code TF} leg ends at a published fix and can be drawn and measured; a
 * {@code VA} leg ends wherever the aircraft happens to reach 800 ft, which depends on the
 * aircraft, the weight and the day. {@link #hasFixedTerminator()} is that distinction, and it is
 * why a procedure cannot simply be flattened into a list of coordinates.
 */
public enum LegType {

    /** Initial fix: the leg has no path, only a starting point. */
    IF("IF", Terminator.FIX),
    /** Track between two fixes — the ordinary leg of an RNAV procedure. */
    TF("TF", Terminator.FIX),
    /** Course to a fix. */
    CF("CF", Terminator.FIX),
    /** Direct to a fix, from wherever the aircraft is. */
    DF("DF", Terminator.FIX),
    /** Course from a fix to an altitude. */
    FA("FA", Terminator.ALTITUDE),
    /** Course from a fix to a manual termination. */
    FM("FM", Terminator.MANUAL),
    /** Course from a fix for a distance. */
    FC("FC", Terminator.DISTANCE),
    /** Course from a fix to a distance from a navaid. */
    FD("FD", Terminator.DISTANCE),
    /** Course to an altitude. */
    CA("CA", Terminator.ALTITUDE),
    /** Course to a distance from a navaid. */
    CD("CD", Terminator.DISTANCE),
    /** Course to an intercept of the next leg. */
    CI("CI", Terminator.INTERCEPT),
    /** Course to a radial from a navaid. */
    CR("CR", Terminator.RADIAL),
    /** Constant-radius arc to a fix. */
    RF("RF", Terminator.FIX),
    /** Arc at a constant distance from a navaid, to a fix. */
    AF("AF", Terminator.FIX),
    /** Heading to an altitude. */
    VA("VA", Terminator.ALTITUDE),
    /** Heading to a distance from a navaid. */
    VD("VD", Terminator.DISTANCE),
    /** Heading to an intercept of the next leg. */
    VI("VI", Terminator.INTERCEPT),
    /** Heading to a manual termination. */
    VM("VM", Terminator.MANUAL),
    /** Heading to a radial from a navaid. */
    VR("VR", Terminator.RADIAL),
    /** Procedure turn, ending at an intercept. */
    PI("PI", Terminator.INTERCEPT),
    /** Hold in lieu of a procedure turn, ending at an altitude. */
    HA("HA", Terminator.ALTITUDE),
    /** Hold, terminating at the fix on the next pass. */
    HF("HF", Terminator.FIX),
    /** Hold, terminating manually. */
    HM("HM", Terminator.MANUAL),
    /** A code this implementation does not know. */
    UNKNOWN("??", Terminator.MANUAL);

    /** What ends a leg. */
    public enum Terminator {
        /** A published fix, so the leg's end is a position on the chart. */
        FIX,
        /** An altitude, so where the leg ends depends on how the aircraft climbs. */
        ALTITUDE,
        /** A distance, from the fix or from a navaid. */
        DISTANCE,
        /** Intercepting the next leg's course. */
        INTERCEPT,
        /** A radial from a navaid. */
        RADIAL,
        /** An instruction from the crew or from air traffic control. */
        MANUAL
    }

    private final String code;
    private final Terminator terminator;

    LegType(String code, Terminator terminator) {
        this.code = code;
        this.terminator = terminator;
    }

    public String code() {
        return code;
    }

    public Terminator terminator() {
        return terminator;
    }

    /** Reads the two-character field, returning {@link #UNKNOWN} for anything unrecognised. */
    public static LegType fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        String cleaned = code.trim().toUpperCase(Locale.ROOT);
        for (LegType type : values()) {
            if (type != UNKNOWN && type.code.equals(cleaned)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * True when the leg ends at a published fix, so its end can be drawn on a map and measured.
     * A leg that ends at an altitude or on a crew instruction cannot be, which is why a coded
     * procedure is not a list of coordinates.
     */
    public boolean hasFixedTerminator() {
        return terminator == Terminator.FIX;
    }

    /** True when the leg is flown on a heading rather than tracking a course. */
    public boolean isHeadingLeg() {
        return code.charAt(0) == 'V';
    }

    /** True when the leg is one of the three holding patterns. */
    public boolean isHold() {
        return this == HA || this == HF || this == HM;
    }

    /** True when the leg only ends when somebody says so, which no plan can predict. */
    public boolean requiresManualTermination() {
        return terminator == Terminator.MANUAL;
    }

    /** True when the leg needs a navaid to define its path or its end. */
    public boolean requiresRecommendedNavaid() {
        return this == AF || this == CD || this == CR || this == FD || this == VD || this == VR;
    }
}
