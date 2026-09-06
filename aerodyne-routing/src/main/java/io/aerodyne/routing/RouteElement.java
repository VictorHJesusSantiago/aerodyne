package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.Fix;

/**
 * One point of an expanded route, and how the route reached it.
 *
 * @param fix        the fix itself
 * @param viaAirway  the airway flown to get here, or {@code null} when the leg was direct
 * @param token      the token in the filed route string this came from; for a point the
 *                   expander inserted while following an airway, the airway's identifier
 * @param implied    true when the point was not written in the route string but lies on an
 *                   airway the route follows
 */
public record RouteElement(Fix fix, String viaAirway, String token, boolean implied) {

    public static RouteElement direct(Fix fix, String token) {
        return new RouteElement(fix, null, token, false);
    }

    public static RouteElement viaAirway(Fix fix, String airway, boolean implied) {
        return new RouteElement(fix, airway, airway, implied);
    }

    public GeoPoint position() {
        return fix.position();
    }

    public String ident() {
        return fix.ident();
    }

    /** True when the leg into this point was flown direct rather than along an airway. */
    public boolean isDirect() {
        return viaAirway == null;
    }

    @Override
    public String toString() {
        return isDirect() ? ident() : viaAirway + " " + ident();
    }
}
