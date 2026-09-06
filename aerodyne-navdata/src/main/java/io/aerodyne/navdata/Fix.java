package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

/**
 * Anything a route can be built out of: a named waypoint, a navaid, an airport reference point,
 * or a coordinate typed into the route string.
 *
 * <p>Identifiers are not unique worldwide — several countries publish a {@code DME} or an
 * {@code AB} — so {@link #icaoRegion()} is what disambiguates them, and route resolution picks
 * the candidate nearest the leg it is building.
 */
public interface Fix {

    /** The published identifier, one to five characters. */
    String ident();

    /** Where the fix is. */
    GeoPoint position();

    /**
     * The two-character ICAO region the fix is published under ({@code EG}, {@code SB},
     * {@code K1}...), or {@code null} for a coordinate that came from the route string.
     */
    String icaoRegion();

    /** What kind of thing this is, for display on the OFP and for filtering route candidates. */
    FixKind kind();
}
