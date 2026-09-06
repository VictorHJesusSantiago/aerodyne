package io.aerodyne.navdata;

/** The categories of {@link Fix} the route builder distinguishes. */
public enum FixKind {

    /** A named enroute or terminal waypoint with no associated radio aid. */
    WAYPOINT,
    /** A VOR, VOR/DME, DME or TACAN. */
    VHF_NAVAID,
    /** A non-directional beacon. */
    NDB_NAVAID,
    /** An airport reference point, usable as the first or last element of a route. */
    AIRPORT,
    /** A runway threshold. */
    RUNWAY,
    /** A latitude/longitude typed directly into the route string. */
    COORDINATE;

    /** True when a route may legally begin or end here. */
    public boolean isTerminal() {
        return this == AIRPORT || this == RUNWAY;
    }
}
