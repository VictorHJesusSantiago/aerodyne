package io.aerodyne.performance;

/**
 * When a cruise may step up to a higher level, and by how much.
 *
 * <p>An aircraft is heaviest at the top of climb and lightest at the top of descent, and the
 * altitude that gives the best specific range rises as the weight falls. A flight that picks one
 * level and holds it is therefore below its optimum for the whole second half. Stepping up
 * recovers most of that, and on a long sector it is worth more than any other single refinement
 * to the profile.
 *
 * <p>What stops it being free is that the step itself costs fuel and time, and that the aircraft
 * has to be given the level. So the policy is expressed the way an operator expresses it: in
 * fixed increments that keep the cruise on usable levels, with a minimum run between steps so a
 * plan does not ask for a climb every fifty miles, and with a cap on how many to ask for.
 *
 * @param incrementFt                the height of one step; 2000 ft keeps the semicircular
 *                                   parity of the level, so a step is still filable
 * @param minimumRunBeforeStepNm     how far to cruise at a level before stepping off it
 * @param minimumRunAfterLastStepNm  how much cruise must remain for a step to be worth taking;
 *                                   a step taken just before the top of descent costs its fuel
 *                                   and never earns it back
 * @param maximumSteps               how many steps to plan at most
 */
public record StepClimbPolicy(double incrementFt,
                              double minimumRunBeforeStepNm,
                              double minimumRunAfterLastStepNm,
                              int maximumSteps) {

    /** No stepping: the cruise is flown at one level throughout. */
    public static final StepClimbPolicy NONE = new StepClimbPolicy(0.0, 0.0, 0.0, 0);

    public StepClimbPolicy {
        if (incrementFt < 0.0) {
            throw new IllegalArgumentException("a step cannot be downwards: " + incrementFt);
        }
        if (maximumSteps < 0) {
            throw new IllegalArgumentException("maximumSteps cannot be negative: " + maximumSteps);
        }
    }

    /**
     * The ordinary policy for a jet in upper airspace: 2000 ft at a time, no more than two
     * steps, at least 200 nm at a level and at least 200 nm of cruise left to earn the step back.
     */
    public static StepClimbPolicy standard() {
        return new StepClimbPolicy(2000.0, 200.0, 200.0, 2);
    }

    /** True when this policy permits any step at all. */
    public boolean isEnabled() {
        return maximumSteps > 0 && incrementFt > 0.0;
    }
}
