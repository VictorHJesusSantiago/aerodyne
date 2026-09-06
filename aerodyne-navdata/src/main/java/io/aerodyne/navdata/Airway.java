package io.aerodyne.navdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An enroute airway: an ordered chain of fixes, assembled from the ARINC 424 {@code ER}
 * records that share a route identifier.
 *
 * <p>Each record describes one <em>point</em> on the airway, not a leg. The altitude limits and
 * the direction restriction a record carries apply to the leg that <em>leaves</em> that point,
 * which is why {@link #segments()} pairs each point with its successor and takes the limits
 * from the earlier of the two.
 */
public record Airway(String ident, List<AirwayPoint> points) {

    public Airway {
        Objects.requireNonNull(ident, "ident");
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        if (points.size() < 2) {
            throw new IllegalArgumentException(
                    "airway " + ident + " needs at least two points, got " + points.size());
        }
    }

    /**
     * The legs of the airway, in sequence order.
     *
     * <p>An airway is not always a single unbroken chain: a route identifier can be reused for
     * disjoint stretches, and ARINC marks the break with an end-of-chain description code. Legs
     * are not emitted across such a break.
     */
    public List<AirwaySegment> segments() {
        List<AirwaySegment> segments = new ArrayList<>(points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            AirwayPoint from = points.get(i);
            AirwayPoint to = points.get(i + 1);
            if (from.endsChain()) {
                continue;
            }
            segments.add(new AirwaySegment(ident, from, to));
        }
        return List.copyOf(segments);
    }

    /** The point on this airway with the given identifier, or empty when it is not on it. */
    public java.util.Optional<AirwayPoint> pointNamed(String fixIdent) {
        return points.stream().filter(p -> p.fixIdent().equalsIgnoreCase(fixIdent)).findFirst();
    }

    /** True when both fixes lie on this airway, which is what makes {@code A .. B} filable. */
    public boolean connects(String fromIdent, String toIdent) {
        return pointNamed(fromIdent).isPresent() && pointNamed(toIdent).isPresent();
    }

    /**
     * The stretch of the airway between two of its fixes, in the order given. Returns an empty
     * list when either fix is not on the airway, or when the direction is not permitted.
     */
    public List<AirwayPoint> between(String fromIdent, String toIdent) {
        int fromIndex = indexOf(fromIdent);
        int toIndex = indexOf(toIdent);
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
            return List.of();
        }
        if (fromIndex < toIndex) {
            List<AirwayPoint> stretch = points.subList(fromIndex, toIndex + 1);
            return traversable(stretch, true) ? List.copyOf(stretch) : List.of();
        }
        List<AirwayPoint> reversed = new ArrayList<>(points.subList(toIndex, fromIndex + 1));
        java.util.Collections.reverse(reversed);
        return traversable(reversed, false) ? List.copyOf(reversed) : List.of();
    }

    private boolean traversable(List<AirwayPoint> stretch, boolean forward) {
        for (int i = 0; i < stretch.size() - 1; i++) {
            // The restriction belongs to the record earlier in sequence order, which when
            // travelling backwards is the *later* element of the reversed stretch.
            AirwayPoint owner = forward ? stretch.get(i) : stretch.get(i + 1);
            if (forward ? !owner.direction().allowsForward() : !owner.direction().allowsBackward()) {
                return false;
            }
            if (owner.endsChain()) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(String fixIdent) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).fixIdent().equalsIgnoreCase(fixIdent)) {
                return i;
            }
        }
        return -1;
    }
}
