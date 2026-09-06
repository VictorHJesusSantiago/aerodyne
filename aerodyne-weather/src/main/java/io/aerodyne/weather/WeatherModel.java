package io.aerodyne.weather;

import io.aerodyne.geo.GeoPoint;

import java.time.Instant;

/**
 * A source of upper-air conditions.
 *
 * <p>The fuel integration asks this once per route sample, so implementations must be cheap and
 * must answer for <em>any</em> position and altitude rather than only where they hold data:
 * a plan that failed at the edge of a regional forecast would be worse than one that degrades
 * to the standard atmosphere and says so. {@link #covers} is what lets a caller find out which
 * of the two it got.
 */
public interface WeatherModel {

    /** Conditions at a position, pressure altitude and time. */
    AtmosphericConditions at(GeoPoint position, double pressureAltitudeFt, Instant validAt);

    /** True when the model holds real data for this position and time. */
    boolean covers(GeoPoint position, Instant validAt);

    /** When the forecast this model came from was issued. */
    Instant issuedAt();

    /** A short description of where the data came from, for the OFP header. */
    String source();

    /** Just the wind, which is all the ground-speed calculation needs. */
    default WindVector windAt(GeoPoint position, double pressureAltitudeFt, Instant validAt) {
        return at(position, pressureAltitudeFt, validAt).wind();
    }

    /**
     * An upper bound on the wind speed anywhere this model answers for.
     *
     * <p>Nothing in the fuel integration needs this. A wind-optimal route search does, and it
     * needs it to be a genuine bound rather than a typical value: the search's heuristic is the
     * direct distance divided by the fastest ground speed attainable, and a heuristic built on
     * an underestimate of the wind would overestimate the time remaining, stop being admissible,
     * and quietly return routes that are not the quickest.
     *
     * <p>The default is deliberately generous, because a model that does not know its own
     * maximum must not claim a small one. An implementation that can measure its data should
     * override this: a tighter bound is a faster search, and only that.
     */
    default double strongestWindKt() {
        return 300.0;
    }
}
