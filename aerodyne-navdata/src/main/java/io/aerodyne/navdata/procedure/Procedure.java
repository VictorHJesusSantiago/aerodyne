package io.aerodyne.navdata.procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A published terminal procedure: a departure, an arrival, or an approach.
 *
 * <p>A procedure is stored as its segments rather than as a single sequence of legs, because
 * that is what it is. {@code MODMI1J} out of Heathrow is a different set of legs depending on
 * which runway is in use and where the flight is going, and the published data holds each
 * combination once rather than every product of them. {@link #assemble} takes the runway and the
 * enroute point and joins the three segments into the legs actually flown.
 *
 * @param airportIcao the aerodrome the procedure belongs to
 * @param ident       the procedure's published name, e.g. {@code MODMI1J}
 * @param type        departure, arrival or approach
 * @param routeType   the ARINC route type of the first segment read, which for an approach says
 *                    what kind of approach it is
 * @param transitions the segments, in the order they were read
 */
public record Procedure(String airportIcao,
                        String ident,
                        ProcedureType type,
                        char routeType,
                        List<ProcedureTransition> transitions) {

    public Procedure {
        Objects.requireNonNull(airportIcao, "airportIcao");
        Objects.requireNonNull(ident, "ident");
        Objects.requireNonNull(type, "type");
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "procedure " + ident + " at " + airportIcao + " has no legs at all");
        }
    }

    /** The segments that depend on the runway in use. */
    public List<ProcedureTransition> runwayTransitions() {
        return transitionsOfKind(TransitionKind.RUNWAY);
    }

    /** The segments that depend on where the flight joins or leaves the enroute structure. */
    public List<ProcedureTransition> enrouteTransitions() {
        return transitionsOfKind(TransitionKind.ENROUTE);
    }

    /** The segments that lead in to an approach from an initial approach fix. */
    public List<ProcedureTransition> approachTransitions() {
        return transitionsOfKind(TransitionKind.APPROACH_TRANSITION);
    }

    /** The segment every flight on the procedure shares, when there is one. */
    public Optional<ProcedureTransition> commonRoute() {
        return transitions.stream()
                .filter(transition -> transition.kind().isCommonToAll())
                .findFirst();
    }

    /** The runways this procedure serves, as the data names them. */
    public List<String> runways() {
        return runwayTransitions().stream().map(ProcedureTransition::identifier).toList();
    }

    /** True when the procedure has a segment for a runway, or has no runway-specific segments. */
    public boolean servesRunway(String runwayDesignator) {
        List<ProcedureTransition> runwaySegments = runwayTransitions();
        if (runwaySegments.isEmpty()) {
            return true;
        }
        return runwaySegments.stream().anyMatch(t -> t.servesRunway(runwayDesignator));
    }

    /**
     * Joins the segments into the legs actually flown.
     *
     * <p>A departure is flown runway segment first, then the common route, then the enroute
     * segment; an arrival is flown the other way about. An approach is flown transition then
     * final. Segments that were not asked for, or that the procedure does not publish, are
     * simply absent — a procedure with no runway segment is one that flies the same way off
     * every runway, not one that is missing something.
     *
     * @param runwayDesignator  the runway in use, or {@code null} to take the first published
     * @param transitionIdent   the enroute or approach transition, or {@code null} for the first
     * @return the legs in flight order; empty when neither segment matched and there is no
     *         common route, which means the combination asked for is not published
     */
    public List<ProcedureLeg> assemble(String runwayDesignator, String transitionIdent) {
        Optional<ProcedureTransition> runway = selectRunway(runwayDesignator);
        Optional<ProcedureTransition> enroute = selectTransition(transitionIdent);
        Optional<ProcedureTransition> common = commonRoute();

        List<ProcedureTransition> ordered = new ArrayList<>(3);
        if (type.isDeparture()) {
            runway.ifPresent(ordered::add);
            common.ifPresent(ordered::add);
            enroute.ifPresent(ordered::add);
        } else {
            enroute.ifPresent(ordered::add);
            common.ifPresent(ordered::add);
            runway.ifPresent(ordered::add);
        }

        List<ProcedureLeg> legs = new ArrayList<>();
        ordered.forEach(transition -> legs.addAll(transition.legs()));
        return List.copyOf(legs);
    }

    /** The whole procedure the way it is flown off the first runway to the first transition. */
    public List<ProcedureLeg> assemble() {
        return assemble(null, null);
    }

    /** True when the procedure is an approach, and a precision one. */
    public boolean isPrecisionApproach() {
        return type == ProcedureType.APPROACH && ProcedureType.isPrecision(routeType);
    }

    /** What kind of approach this is, for an approach procedure. */
    public String approachKind() {
        return type == ProcedureType.APPROACH
                ? ProcedureType.approachKind(routeType)
                : type.displayName();
    }

    /**
     * True when every leg of the assembled procedure ends at a published fix, so the whole
     * thing can be drawn and measured. Departures routinely fail this — a {@code VA} climb on
     * runway heading to 800 ft is not a position — and a caller that needs a drawable path has
     * to know that before it tries.
     */
    public boolean isFullyResolvable(String runwayDesignator, String transitionIdent) {
        return assemble(runwayDesignator, transitionIdent).stream()
                .allMatch(ProcedureLeg::endsAtFix);
    }

    /** The identifiers of the fixes the assembled procedure passes through, in order. */
    public List<String> fixSequence(String runwayDesignator, String transitionIdent) {
        return assemble(runwayDesignator, transitionIdent).stream()
                .filter(ProcedureLeg::endsAtFix)
                .map(ProcedureLeg::fixIdent)
                .toList();
    }

    private Optional<ProcedureTransition> selectRunway(String runwayDesignator) {
        List<ProcedureTransition> candidates = runwayTransitions();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (runwayDesignator == null) {
            return Optional.of(candidates.get(0));
        }
        return candidates.stream().filter(t -> t.servesRunway(runwayDesignator)).findFirst();
    }

    private Optional<ProcedureTransition> selectTransition(String transitionIdent) {
        List<ProcedureTransition> candidates = type == ProcedureType.APPROACH
                ? approachTransitions()
                : enrouteTransitions();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (transitionIdent == null) {
            return Optional.of(candidates.get(0));
        }
        String wanted = ProcedureType.normaliseIdent(transitionIdent);
        return candidates.stream()
                .filter(t -> ProcedureType.normaliseIdent(t.identifier()).equals(wanted))
                .findFirst();
    }

    private List<ProcedureTransition> transitionsOfKind(TransitionKind kind) {
        return transitions.stream().filter(t -> t.kind() == kind).toList();
    }

    @Override
    public String toString() {
        return type.displayName() + " " + ident + " at " + airportIcao
                + " (" + transitions.size() + " segments)";
    }
}
