package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.NavigationDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a filed route string into the sequence of positions it actually describes.
 *
 * <p>A route string is terse by design and most of what it says is implicit. {@code EGLL DCT
 * KOLIN UN862 BEDEK DCT LEBL} names five things but describes a path through however many fixes
 * {@code UN862} happens to cross between {@code KOLIN} and {@code BEDEK}. Expanding it means:
 *
 * <ul>
 *   <li><b>Resolving identifiers positionally.</b> Fix identifiers repeat worldwide, so each one
 *       is resolved against where the route has reached rather than in isolation.</li>
 *   <li><b>Walking airways.</b> An airway token followed by a fix means "follow this airway from
 *       where I am to there", and every intervening point belongs to the route.</li>
 *   <li><b>Ignoring what is not a place.</b> Speed and level groups, {@code DCT}, and the
 *       {@code /N0450F350} suffixes carry no position and are skipped.</li>
 * </ul>
 *
 * <p>Nothing here fails the whole route over one bad token. A route that arrives from an airline
 * system against last cycle's data will have something in it that no longer resolves, and the
 * useful response is the route minus that point plus a warning saying so — which is what
 * {@link ExpandedRoute#warnings()} carries.
 */
public final class RouteExpander {

    /** {@code N0450F350}, {@code M085F350}, {@code K0900S1010}: a speed and a level, not a place. */
    private static final Pattern SPEED_LEVEL = Pattern.compile(
            "^([NMK]\\d{3,4}|M\\d{2,3})([AFSM]\\d{3,4})?$");
    /** A bare level: {@code F350}, {@code A045}, {@code S1010}. */
    private static final Pattern LEVEL_ONLY = Pattern.compile("^[AFSM]\\d{3,4}$");
    /** Tokens that mark a leg as direct rather than naming anything. */
    private static final List<String> DIRECT_TOKENS = List.of("DCT", "DIRECT", "..", ".");
    /** Tokens that say something about the flight rather than the path. */
    private static final List<String> IGNORED_TOKENS = List.of("IFR", "VFR", "SID", "STAR", "T");

    private final NavigationDatabase database;

    public RouteExpander(NavigationDatabase database) {
        this.database = database;
    }

    /**
     * Expands a route string.
     *
     * @param routeString the filed route, which may or may not include the aerodromes
     * @param departure   where the route starts, used to resolve the first identifier; may be
     *                    {@code null}, in which case the first token must be unambiguous
     */
    public ExpandedRoute expand(String routeString, GeoPoint departure) {
        List<RouteElement> elements = new ArrayList<>();
        List<RouteWarning> warnings = new ArrayList<>();
        if (routeString == null || routeString.isBlank()) {
            return new ExpandedRoute(routeString == null ? "" : routeString, elements, warnings);
        }

        String[] tokens = routeString.trim().toUpperCase(Locale.ROOT).split("[\\s]+");
        GeoPoint reached = departure;

        for (int i = 0; i < tokens.length; i++) {
            // A "/" suffix carries a speed and level change; the place is what precedes it.
            String token = tokens[i].split("/")[0];
            if (token.isEmpty() || isNotAPlace(token)) {
                continue;
            }

            Optional<Airway> airway = database.airway(token);
            if (airway.isPresent() && !elements.isEmpty() && i + 1 < tokens.length) {
                String exitToken = tokens[i + 1].split("/")[0];
                AirwayWalk walk = walkAirway(airway.get(), elements, exitToken, reached);
                if (walk.succeeded()) {
                    elements.addAll(walk.elements());
                    reached = elements.get(elements.size() - 1).position();
                    i++;
                    continue;
                }
                warnings.add(RouteWarning.note(token, walk.reason()));
                // Fall through: the token may still name a fix, and if not the next token is
                // resolved on its own, which turns the airway leg into a direct one.
                continue;
            }

            Optional<Fix> fix = database.resolve(token, reached);
            if (fix.isEmpty()) {
                warnings.add(RouteWarning.dropped(token, describeFailure(token, reached)));
                continue;
            }
            elements.add(RouteElement.direct(fix.get(), token));
            reached = fix.get().position();
        }

        return new ExpandedRoute(routeString.trim(), elements, warnings);
    }

    /** Expands a route with no departure context; every identifier must resolve on its own. */
    public ExpandedRoute expand(String routeString) {
        return expand(routeString, null);
    }

    /**
     * Follows an airway from the point the route has reached to a named exit.
     *
     * <p>The entry point is the last element already on the route. If that fix is not on the
     * airway the leg cannot be flown as filed — a route saying {@code OCK UN862 BEDEK} when
     * {@code UN862} does not pass through {@code OCK} is describing something that does not
     * exist, and inventing a join would be worse than saying so.
     */
    private AirwayWalk walkAirway(Airway airway, List<RouteElement> routeSoFar,
                                  String exitToken, GeoPoint reached) {
        String entryIdent = routeSoFar.get(routeSoFar.size() - 1).ident();
        if (airway.pointNamed(entryIdent).isEmpty()) {
            return AirwayWalk.failed(
                    airway.ident() + " does not pass through " + entryIdent + "; flying direct");
        }
        if (airway.pointNamed(exitToken).isEmpty()) {
            return AirwayWalk.failed(
                    airway.ident() + " does not pass through " + exitToken + "; flying direct");
        }
        List<AirwayPoint> stretch = airway.between(entryIdent, exitToken);
        if (stretch.isEmpty()) {
            return AirwayWalk.failed(airway.ident() + " cannot be flown from " + entryIdent
                    + " to " + exitToken + " in that direction; flying direct");
        }

        List<RouteElement> expanded = new ArrayList<>(stretch.size());
        GeoPoint from = reached;
        // The first element of the stretch is the entry point, which is already on the route.
        for (int i = 1; i < stretch.size(); i++) {
            AirwayPoint point = stretch.get(i);
            Optional<Fix> fix = resolveAirwayPoint(point, from);
            if (fix.isEmpty()) {
                return AirwayWalk.failed("cannot resolve " + point.fixIdent() + " on "
                        + airway.ident() + "; flying direct");
            }
            boolean implied = i < stretch.size() - 1;
            expanded.add(RouteElement.viaAirway(fix.get(), airway.ident(), implied));
            from = fix.get().position();
        }
        return AirwayWalk.succeeded(expanded);
    }

    /**
     * Resolves a fix named by an airway record. The record carries the ICAO region, which
     * settles an ambiguous identifier outright; where it does not, the position the route has
     * reached does.
     */
    private Optional<Fix> resolveAirwayPoint(AirwayPoint point, GeoPoint reached) {
        Optional<Fix> byRegion = database.resolve(point.fixIdent(), point.fixRegion());
        return byRegion.isPresent() ? byRegion : database.resolve(point.fixIdent(), reached);
    }

    /** True for a token that carries no position: a speed, a level, or a routing keyword. */
    private static boolean isNotAPlace(String token) {
        return DIRECT_TOKENS.contains(token)
                || IGNORED_TOKENS.contains(token)
                || SPEED_LEVEL.matcher(token).matches()
                || LEVEL_ONLY.matcher(token).matches();
    }

    /** Says why an identifier did not resolve, which is more use than saying that it did not. */
    private String describeFailure(String token, GeoPoint reached) {
        int candidates = database.allNamed(token).size();
        if (candidates == 0) {
            return "no fix, navaid or airport with this identifier in the navigation database";
        }
        if (reached == null) {
            return candidates + " fixes share this identifier and there is no position to "
                    + "resolve it against";
        }
        return candidates + " fixes share this identifier and none could be chosen";
    }

    /** The outcome of following an airway: either the points it crossed, or why it could not. */
    private record AirwayWalk(List<RouteElement> elements, String reason) {

        static AirwayWalk succeeded(List<RouteElement> elements) {
            return new AirwayWalk(elements, null);
        }

        static AirwayWalk failed(String reason) {
            return new AirwayWalk(List.of(), reason);
        }

        boolean succeeded() {
            return reason == null;
        }
    }
}
