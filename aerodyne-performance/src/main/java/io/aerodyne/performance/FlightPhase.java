package io.aerodyne.performance;

/** The vertical phase a leg is flown in. */
public enum FlightPhase {

    CLIMB,
    CRUISE,
    /**
     * A step climb: a short climb to the next usable level partway through the cruise, flown
     * because the aircraft has burnt enough fuel for the higher level to pay.
     *
     * <p>It is kept apart from {@link #CLIMB} because the two answer different questions. The
     * initial climb is a phase of the flight and the OFP totals it as one. A step is an event
     * inside the cruise, and totalling it into the climb would make a plan's climb fuel
     * unrecognisable against the same route flown level.
     */
    STEP_CLIMB,
    DESCENT;

    /** True for the phase whose fuel flow is read from the cruise table. */
    public boolean isCruise() {
        return this == CRUISE;
    }

    /** True for a phase that gains altitude, whichever of the two it is. */
    public boolean isClimbing() {
        return this == CLIMB || this == STEP_CLIMB;
    }
}
