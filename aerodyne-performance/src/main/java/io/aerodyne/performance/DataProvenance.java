package io.aerodyne.performance;

/**
 * Where an aircraft type's performance figures came from.
 *
 * <p>This travels with the type because it has to reach the dispatch decision. A plan computed
 * on figures nobody certified looks exactly like a plan computed on a flight manual — the fuel
 * comes out in kilograms either way — and the only thing that distinguishes them is a record of
 * their provenance. Without it, a demonstration and a dispatch are indistinguishable at the
 * point where the difference matters.
 */
public enum DataProvenance {

    /** Figures from the manufacturer's flight manual, as amended for this airframe. */
    MANUFACTURER,

    /**
     * Figures constructed to be representative of a class of aeroplane. Internally consistent
     * and good enough to demonstrate or to test against, and not good enough to fly on.
     */
    REPRESENTATIVE;

    /** True when a flight may be dispatched on these figures. */
    public boolean isDispatchable() {
        return this == MANUFACTURER;
    }

    /** Why not, when it is not. */
    public String objection() {
        return "the aircraft performance figures are representative rather than manufacturer "
                + "data, and no flight may be dispatched on them";
    }
}
