package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureLeg;
import io.aerodyne.navdata.procedure.ProcedureType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Joins a departure and an arrival procedure onto an enroute route.
 *
 * <p>A filed route does not begin at the aerodrome and strike out for the first airway. It
 * begins with a departure procedure, which takes the aircraft from a particular runway to a
 * particular point in the enroute structure, and it ends with an arrival that does the reverse.
 * Those procedures are most of the track flown in the first and last hundred miles, and a plan
 * that leaves them out understates both the distance and the fuel.
 *
 * <p>Two things make attaching them more than concatenation.
 *
 * <p><b>Not every leg has a position.</b> A departure commonly begins by climbing on runway
 * heading to an altitude, and where that ends depends on the aircraft and the day. Those legs
 * are dropped from the path — there is nothing to draw — and the fact is reported, because a
 * distance that silently omits the first four miles is worse than one that says it did.
 *
 * <p><b>The join has to match.</b> A procedure is only usable if it ends where the route begins.
 * When the caller names one, it is checked and refused if it does not fit; when the caller names
 * none, one is chosen precisely because it fits. Bolting on a procedure that ends somewhere else
 * would produce a route with a jump in it that nothing downstream could detect.
 */
public final class ProcedureAttacher {

    private final NavigationDatabase database;

    public ProcedureAttacher(NavigationDatabase database) {
        this.database = database;
    }

    /**
     * What was attached, and what could not be.
     *
     * @param route            the route with its procedures joined on
     * @param departureUsed    the departure procedure attached, if any
     * @param arrivalUsed      the arrival procedure attached, if any
     */
    public record Result(ExpandedRoute route, String departureUsed, String arrivalUsed) {

        public Optional<String> departure() {
            return Optional.ofNullable(departureUsed);
        }

        public Optional<String> arrival() {
            return Optional.ofNullable(arrivalUsed);
        }
    }

    /**
     * Attaches procedures to an enroute route.
     *
     * @param enroute          the route between the aerodromes, its first element the departure
     *                         and its last the destination
     * @param departure        the departure aerodrome
     * @param destination      the destination
     * @param departureRunway  the runway in use, or {@code null} for the first published
     * @param sidIdent         the departure procedure to use, or {@code null} to choose
     * @param arrivalRunway    the expected landing runway, or {@code null}
     * @param starIdent        the arrival procedure, or {@code null} to choose
     */
    public Result attach(ExpandedRoute enroute, Airport departure, Airport destination,
                         String departureRunway, String sidIdent,
                         String arrivalRunway, String starIdent) {
        List<RouteElement> elements = new ArrayList<>(enroute.elements());
        List<RouteWarning> warnings = new ArrayList<>(enroute.warnings());
        if (elements.size() < 2) {
            return new Result(enroute, null, null);
        }

        // The first and last enroute points, which the procedures have to join onto. Element 0
        // is the departure aerodrome and the last is the destination.
        String firstEnroute = elements.size() > 2 ? elements.get(1).ident() : null;
        String lastEnroute = elements.size() > 2
                ? elements.get(elements.size() - 2).ident()
                : null;

        Attached sid = select(departure, ProcedureType.SID, departureRunway, sidIdent,
                firstEnroute, warnings);
        Attached star = select(destination, ProcedureType.STAR, arrivalRunway, starIdent,
                lastEnroute, warnings);

        if (sid.procedure() != null) {
            // After the departure aerodrome, before the first enroute point, minus any repeat
            // of the point they meet at.
            List<RouteElement> legs = new ArrayList<>(sid.elements());
            if (!legs.isEmpty() && firstEnroute != null
                    && legs.get(legs.size() - 1).ident().equalsIgnoreCase(firstEnroute)) {
                legs.remove(legs.size() - 1);
            }
            elements.addAll(1, legs);
        }
        if (star.procedure() != null) {
            List<RouteElement> legs = new ArrayList<>(star.elements());
            if (!legs.isEmpty() && lastEnroute != null
                    && legs.get(0).ident().equalsIgnoreCase(lastEnroute)) {
                legs.remove(0);
            }
            elements.addAll(elements.size() - 1, legs);
        }

        String routeString = renderRouteString(enroute, sid, star);
        return new Result(new ExpandedRoute(routeString, elements, warnings),
                sid.identifier(), star.identifier());
    }

    /**
     * Picks the procedure to use and resolves its legs to positions.
     *
     * <p>A named procedure that does not exist, does not serve the runway, or does not reach the
     * route is a warning and no procedure: the plan is still flyable without it, and refusing
     * the whole request over a departure that could be flown another way would be worse than
     * saying the departure was not attached.
     */
    private Attached select(Airport aerodrome, ProcedureType type, String runway, String ident,
                            String joinFix, List<RouteWarning> warnings) {
        List<Procedure> candidates = database.procedures(aerodrome.icaoCode(), type);
        if (candidates.isEmpty()) {
            if (ident != null) {
                warnings.add(RouteWarning.dropped(ident,
                        "no " + type.displayName() + " is published for " + aerodrome.icaoCode()));
            }
            return Attached.none();
        }

        Optional<Procedure> chosen = ident != null
                ? named(candidates, ident, runway, warnings)
                : joining(candidates, runway, joinFix);
        if (chosen.isEmpty()) {
            if (ident == null && joinFix != null) {
                warnings.add(RouteWarning.note(aerodrome.icaoCode(),
                        "no " + type.displayName() + " joins the route at " + joinFix
                                + "; planning from the aerodrome"));
            }
            return Attached.none();
        }

        Procedure procedure = chosen.get();
        List<ProcedureLeg> legs = procedure.assemble(runway, null);
        List<RouteElement> resolved = new ArrayList<>(legs.size());
        int unresolvable = 0;
        GeoPoint from = aerodrome.position();

        for (ProcedureLeg leg : legs) {
            if (!leg.endsAtFix()) {
                unresolvable++;
                continue;
            }
            Optional<Fix> fix = database.resolve(leg.fixIdent(), from);
            if (fix.isEmpty()) {
                unresolvable++;
                continue;
            }
            resolved.add(RouteElement.viaAirway(fix.get(), procedure.ident(), true));
            from = fix.get().position();
        }

        if (unresolvable > 0) {
            warnings.add(RouteWarning.note(procedure.ident(), unresolvable + " of its "
                    + legs.size() + " legs end at an altitude or a heading rather than a fix, "
                    + "and are not part of the measured track"));
        }
        if (resolved.isEmpty()) {
            return Attached.none();
        }
        // The last element of a procedure is the one a filed route names; the rest are implied.
        resolved.set(resolved.size() - 1,
                new RouteElement(resolved.get(resolved.size() - 1).fix(), procedure.ident(),
                        procedure.ident(), false));
        return new Attached(procedure, procedure.ident(), resolved);
    }

    /** The named procedure, checked against the runway and reported when it does not fit. */
    private static Optional<Procedure> named(List<Procedure> candidates, String ident,
                                             String runway, List<RouteWarning> warnings) {
        String wanted = ProcedureType.normaliseIdent(ident);
        Optional<Procedure> match = candidates.stream()
                .filter(p -> ProcedureType.normaliseIdent(p.ident()).equals(wanted))
                .findFirst();
        if (match.isEmpty()) {
            warnings.add(RouteWarning.dropped(ident, "no such procedure at this aerodrome"));
            return Optional.empty();
        }
        if (runway != null && !match.get().servesRunway(runway)) {
            warnings.add(RouteWarning.dropped(ident,
                    "is not published for runway " + runway));
            return Optional.empty();
        }
        return match;
    }

    /** The procedure that ends where the route begins, which is what makes it usable. */
    private static Optional<Procedure> joining(List<Procedure> candidates, String runway,
                                               String joinFix) {
        if (joinFix == null) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(p -> runway == null || p.servesRunway(runway))
                .filter(p -> reaches(p, runway, joinFix))
                .findFirst();
    }

    /** True when any transition of the procedure passes through the joining fix. */
    private static boolean reaches(Procedure procedure, String runway, String joinFix) {
        if (procedure.fixSequence(runway, null).stream().anyMatch(joinFix::equalsIgnoreCase)) {
            return true;
        }
        // An enroute transition is chosen by where the route goes, so try each in turn rather
        // than only the first, which assemble() would otherwise pick for us.
        return procedure.enrouteTransitions().stream()
                .anyMatch(transition -> procedure
                        .fixSequence(runway, transition.identifier()).stream()
                        .anyMatch(joinFix::equalsIgnoreCase));
    }

    /**
     * The route as it would be filed: the aerodrome, the departure, the enroute route, the
     * arrival, the destination. Procedures are named rather than expanded, which is how a flight
     * plan writes them and how a controller reads them back.
     */
    private static String renderRouteString(ExpandedRoute enroute, Attached sid, Attached star) {
        List<String> tokens = new ArrayList<>();
        String[] enrouteTokens = enroute.toRouteString().split("\\s+");

        for (int i = 0; i < enrouteTokens.length; i++) {
            tokens.add(enrouteTokens[i]);
            if (i == 0 && sid.identifier() != null) {
                tokens.add(sid.identifier());
            }
            if (i == enrouteTokens.length - 2 && star.identifier() != null) {
                tokens.add(star.identifier());
            }
        }
        if (enrouteTokens.length == 1 && star.identifier() != null) {
            tokens.add(star.identifier());
        }
        return String.join(" ", tokens);
    }

    /** A procedure and the route elements it contributes. */
    private record Attached(Procedure procedure, String identifier, List<RouteElement> elements) {

        static Attached none() {
            return new Attached(null, null, List.of());
        }
    }
}
