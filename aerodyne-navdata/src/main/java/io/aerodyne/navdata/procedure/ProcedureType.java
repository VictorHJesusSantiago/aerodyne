package io.aerodyne.navdata.procedure;

import java.util.Locale;

/**
 * The three kinds of terminal procedure, identified by their ARINC 424 subsection code.
 *
 * <p>The route type field means different things in each. A departure numbers its runway
 * transitions {@code 1} and its enroute transitions {@code 3}; an arrival does the opposite,
 * because it is flown the other way round. Getting that backwards would assemble a procedure
 * that starts in the enroute structure and ends at a runway it never approached, so the mapping
 * lives here rather than in the parser.
 */
public enum ProcedureType {

    /** {@code PD}: a standard instrument departure. */
    SID('D'),
    /** {@code PE}: a standard terminal arrival. */
    STAR('E'),
    /** {@code PF}: an instrument approach. */
    APPROACH('F');

    private final char subsectionCode;

    ProcedureType(char subsectionCode) {
        this.subsectionCode = subsectionCode;
    }

    public char subsectionCode() {
        return subsectionCode;
    }

    /** The procedure type a subsection code names, or {@code null} for anything else. */
    public static ProcedureType fromSubsection(char subsection) {
        for (ProcedureType type : values()) {
            if (type.subsectionCode == Character.toUpperCase(subsection)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Which part of the procedure a record belongs to.
     *
     * <p>Departures and arrivals are told apart by their route type: a departure's {@code 1},
     * {@code 4}, {@code F} and {@code T} are runway transitions and its {@code 3}, {@code 6},
     * {@code S} and {@code V} are enroute transitions, while an arrival's are the other way
     * round. The digits, the {@code 4}-{@code 6} range and the letters are the conventional,
     * RNAV and FMS codings of the same three things.
     *
     * <p>An approach has no route-type split: its route type says what kind of approach it is —
     * {@code I} for an ILS, {@code R} for an RNAV — and what separates the transitions from the
     * final approach is whether the record names a transition at all.
     */
    public TransitionKind transitionKindFor(char routeType, String transitionIdent) {
        char code = Character.toUpperCase(routeType);
        if (this == APPROACH) {
            return transitionIdent == null || transitionIdent.isBlank()
                    ? TransitionKind.FINAL
                    : TransitionKind.APPROACH_TRANSITION;
        }
        boolean runwayEnd = switch (code) {
            case '1', '4', 'F', 'T' -> this == SID;
            case '3', '6', 'S', 'V' -> this == STAR;
            default -> false;
        };
        if (runwayEnd) {
            return TransitionKind.RUNWAY;
        }
        boolean enrouteEnd = switch (code) {
            case '3', '6', 'S', 'V' -> this == SID;
            case '1', '4', 'F' -> this == STAR;
            default -> false;
        };
        if (enrouteEnd) {
            return TransitionKind.ENROUTE;
        }
        // 2, 5 and M are the common route; anything unrecognised is treated as one, since a
        // procedure whose middle is missing is less use than one with an odd segment in it.
        return TransitionKind.COMMON;
    }

    /** True for a procedure flown outbound from the aerodrome. */
    public boolean isDeparture() {
        return this == SID;
    }

    public String displayName() {
        return switch (this) {
            case SID -> "SID";
            case STAR -> "STAR";
            case APPROACH -> "approach";
        };
    }

    /**
     * What an approach's route type letter names. Only meaningful for {@link #APPROACH}; the
     * letter is the difference between an ILS and an RNAV to the same runway, which decides the
     * minima and whether the aerodrome counts as having a precision approach.
     */
    public static String approachKind(char routeType) {
        return switch (Character.toUpperCase(routeType)) {
            case 'I' -> "ILS";
            case 'X' -> "LOC";
            case 'R' -> "RNAV";
            case 'D' -> "VOR/DME";
            case 'V' -> "VOR";
            case 'N' -> "NDB";
            case 'Q' -> "NDB/DME";
            case 'S' -> "VOR/DME (RNAV)";
            case 'P' -> "GPS";
            case 'J' -> "GLS";
            case 'M' -> "MLS";
            case 'U' -> "SDF";
            case 'T' -> "TACAN";
            case 'L' -> "LDA";
            default -> String.valueOf(routeType).trim();
        };
    }

    /** True when the approach type letter names a precision approach. */
    public static boolean isPrecision(char routeType) {
        return switch (Character.toUpperCase(routeType)) {
            case 'I', 'J', 'M' -> true;
            default -> false;
        };
    }

    /** Normalises a procedure identifier for comparison: trimmed and upper case. */
    public static String normaliseIdent(String ident) {
        return ident == null ? "" : ident.trim().toUpperCase(Locale.ROOT);
    }
}
