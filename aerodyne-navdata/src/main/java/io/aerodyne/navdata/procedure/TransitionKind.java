package io.aerodyne.navdata.procedure;

/**
 * Which segment of a procedure a set of legs belongs to.
 *
 * <p>A published procedure is not one sequence of legs but three joined at their ends: the part
 * that depends on the runway, the part everyone flies, and the part that depends on where the
 * flight is going. Which one a flight gets is decided at dispatch — or later, by air traffic
 * control — so they are stored apart and joined when the runway and the enroute point are known.
 */
public enum TransitionKind {

    /** The segment that depends on which runway is in use. */
    RUNWAY,
    /** The segment every flight on the procedure flies. */
    COMMON,
    /** The segment that depends on where the flight joins or leaves the enroute structure. */
    ENROUTE,
    /** An approach transition: the way in from an initial approach fix. */
    APPROACH_TRANSITION,
    /** The final approach itself, from the final approach fix to the missed approach point. */
    FINAL;

    /** True when the segment is the same for every flight on the procedure. */
    public boolean isCommonToAll() {
        return this == COMMON || this == FINAL;
    }
}
