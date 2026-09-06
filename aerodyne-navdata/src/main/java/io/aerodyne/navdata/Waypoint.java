package io.aerodyne.navdata;

import io.aerodyne.geo.GeoPoint;

import java.util.Objects;

/**
 * A named position with no radio aid behind it — the {@code EA} (enroute) and {@code PC}
 * (terminal) records of an ARINC 424 file.
 *
 * @param ident       published identifier, e.g. {@code KOLIN}
 * @param icaoRegion  two-character ICAO region code
 * @param position    where it is
 * @param usage       whether the fix is enroute, terminal, or both
 * @param terminalAirport the airport a terminal waypoint belongs to, {@code null} when enroute
 * @param name        the full published name, when the record carries one
 */
public record Waypoint(String ident,
                       String icaoRegion,
                       GeoPoint position,
                       WaypointUsage usage,
                       String terminalAirport,
                       String name) implements Fix {

    public Waypoint {
        Objects.requireNonNull(ident, "ident");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(usage, "usage");
    }

    public static Waypoint enroute(String ident, String icaoRegion, GeoPoint position) {
        return new Waypoint(ident, icaoRegion, position, WaypointUsage.ENROUTE, null, null);
    }

    @Override
    public FixKind kind() {
        return FixKind.WAYPOINT;
    }
}
