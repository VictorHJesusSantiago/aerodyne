package io.aerodyne.dispatch;

import io.aerodyne.geo.GeoPoint;

import java.time.Instant;

/**
 * The point along a route from which two aerodromes are the same flying time away.
 *
 * <p>Before it, the first aerodrome is closer in time; after it, the second is. That makes it
 * the point at which the answer to "where do we go" changes, which is why it is printed on the
 * OFP and briefed rather than merely computed.
 *
 * <p>It is a point of equal <em>time</em>, not equal distance, and the two are not the same
 * place. With a 100-knot westerly on the track, the point where the two are equidistant is well
 * downwind of the point where they are the same number of minutes away, and it is the minutes
 * that matter when an engine has just failed.
 *
 * @param position          where it is
 * @param distanceAlongNm   how far along the route
 * @param reachedAt         when the flight expects to be there
 * @param toFirst           the diversion to the first aerodrome from this point
 * @param toSecond          the diversion to the second
 * @param firstIcao         the first aerodrome's identifier
 * @param secondIcao        the second's
 */
public record EqualTimePoint(GeoPoint position,
                             double distanceAlongNm,
                             Instant reachedAt,
                             DiversionCalculator.Diversion toFirst,
                             DiversionCalculator.Diversion toSecond,
                             String firstIcao,
                             String secondIcao) {

    /** The time to either aerodrome, which by construction is the same to within the tolerance. */
    public double diversionTimeMinutes() {
        return (toFirst.timeMinutes() + toSecond.timeMinutes()) / 2.0;
    }

    /**
     * How far apart the two times actually came out. A search that converged leaves this at
     * well under a minute; a large value means the geometry has no equal-time point on the
     * route at all and the nearest end was returned instead.
     */
    public double imbalanceMinutes() {
        return Math.abs(toFirst.timeMinutes() - toSecond.timeMinutes());
    }

    /** The aerodrome to divert to at a point before this one. */
    public String preferredBefore() {
        return firstIcao;
    }

    /** The aerodrome to divert to at a point after it. */
    public String preferredAfter() {
        return secondIcao;
    }

    /** {@code ETP EGLL/BIKF} — how an OFP labels the point. */
    public String label() {
        return "ETP " + firstIcao + "/" + secondIcao;
    }

    @Override
    public String toString() {
        return label() + " at " + position + " (" + Math.round(diversionTimeMinutes()) + " min each way)";
    }
}
