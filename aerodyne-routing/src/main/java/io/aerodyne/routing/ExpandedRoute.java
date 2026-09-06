package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;

import java.util.List;

/**
 * A filed route resolved against the navigation database: every point it passes through, in
 * order, with the airway legs expanded into the fixes they actually cross.
 *
 * @param routeString the route as filed
 * @param elements    the points, departure first
 * @param warnings    everything the expander could not honour
 */
public record ExpandedRoute(String routeString, List<RouteElement> elements, List<RouteWarning> warnings) {

    public ExpandedRoute {
        elements = List.copyOf(elements);
        warnings = List.copyOf(warnings);
    }

    /** The positions, which is what the fuel integration flies. */
    public List<GeoPoint> path() {
        return elements.stream().map(RouteElement::position).toList();
    }

    /** The route's length along the ground, summed leg by leg. */
    public double distanceNm() {
        double total = 0.0;
        List<GeoPoint> path = path();
        for (int i = 0; i < path.size() - 1; i++) {
            total += Geodesy.distanceNm(path.get(i), path.get(i + 1));
        }
        return total;
    }

    /**
     * How much longer the route is than a great circle between its ends. A route much longer
     * than the direct distance is either avoiding something or is a mistake, and either way it
     * is worth seeing.
     */
    public double excessOverDirectNm() {
        if (elements.size() < 2) {
            return 0.0;
        }
        return distanceNm() - Geodesy.distanceNm(
                elements.get(0).position(), elements.get(elements.size() - 1).position());
    }

    /** True when nothing in the filed route had to be dropped. */
    public boolean isComplete() {
        return warnings.stream().noneMatch(RouteWarning::dropped);
    }

    /** The points actually written in the route string, without the airway fixes in between. */
    public List<RouteElement> filedPoints() {
        return elements.stream().filter(e -> !e.implied()).toList();
    }

    /** The airways the route uses, in order, without repeats. */
    public List<String> airways() {
        return elements.stream()
                .map(RouteElement::viaAirway)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The route rendered back into a filed route string, with consecutive legs on one airway
     * collapsed to {@code AIRWAY EXIT} the way a flight plan writes them.
     */
    public String toRouteString() {
        StringBuilder rendered = new StringBuilder();
        String pendingAirway = null;
        for (int i = 0; i < elements.size(); i++) {
            RouteElement element = elements.get(i);
            boolean lastOfAirway = element.viaAirway() != null
                    && (i == elements.size() - 1
                    || !element.viaAirway().equals(elements.get(i + 1).viaAirway()));
            if (element.viaAirway() == null) {
                append(rendered, element.ident());
                pendingAirway = null;
            } else {
                if (!element.viaAirway().equals(pendingAirway)) {
                    append(rendered, element.viaAirway());
                    pendingAirway = element.viaAirway();
                }
                if (lastOfAirway) {
                    append(rendered, element.ident());
                    pendingAirway = null;
                }
            }
        }
        return rendered.toString();
    }

    private static void append(StringBuilder rendered, String token) {
        if (!rendered.isEmpty()) {
            rendered.append(' ');
        }
        rendered.append(token);
    }
}
