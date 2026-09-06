package io.aerodyne.navdata.procedure;

import java.util.List;
import java.util.Objects;

/**
 * One segment of a procedure: its legs, in the order they are flown.
 *
 * @param kind       which segment this is
 * @param identifier what names it — a runway designator for a runway transition, a fix for an
 *                   enroute one, blank for the common route
 * @param routeType  the ARINC route type character the segment was published under
 * @param legs       the legs, in sequence order
 */
public record ProcedureTransition(TransitionKind kind, String identifier, char routeType,
                                  List<ProcedureLeg> legs) {

    public ProcedureTransition {
        Objects.requireNonNull(kind, "kind");
        identifier = identifier == null ? "" : identifier.trim();
        legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
    }

    /** True when the segment has no identifier, which is what marks the common route. */
    public boolean isCommon() {
        return identifier.isEmpty();
    }

    /** True when the segment names a runway rather than a fix. */
    public boolean namesRunway() {
        return kind == TransitionKind.RUNWAY;
    }

    /**
     * True when this transition serves a runway designator. Runway transitions are published as
     * {@code RW27L}, and a caller asking for runway {@code 27L} means the same thing.
     */
    public boolean servesRunway(String runwayDesignator) {
        if (kind != TransitionKind.RUNWAY || runwayDesignator == null) {
            return false;
        }
        String wanted = runwayDesignator.trim().toUpperCase(java.util.Locale.ROOT);
        String bare = identifier.startsWith("RW") ? identifier.substring(2) : identifier;
        String wantedBare = wanted.startsWith("RW") ? wanted.substring(2) : wanted;
        // A transition published for all runways ends in B, as in RW27B for 27L and 27R.
        if (bare.endsWith("B") && wantedBare.length() >= 2) {
            return bare.substring(0, bare.length() - 1).equals(wantedBare.substring(0, 2));
        }
        return bare.equals(wantedBare);
    }

    /** The legs that end at a published fix, which are the ones a route can be drawn through. */
    public List<ProcedureLeg> resolvableLegs() {
        return legs.stream().filter(ProcedureLeg::endsAtFix).toList();
    }

    /** True when any leg cannot be resolved to a position in advance. */
    public boolean hasUnresolvableLegs() {
        return legs.stream().anyMatch(ProcedureLeg::isUnresolvable);
    }

    @Override
    public String toString() {
        return kind + (isCommon() ? "" : " " + identifier) + " (" + legs.size() + " legs)";
    }
}
