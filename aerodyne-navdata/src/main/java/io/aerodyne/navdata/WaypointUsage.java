package io.aerodyne.navdata;

/**
 * Where a waypoint may be used, taken from the ARINC 424 usage field. A terminal-only fix is
 * not a legal enroute route element, which is why the route resolver filters on this.
 */
public enum WaypointUsage {

    ENROUTE,
    TERMINAL,
    BOTH;

    /**
     * Reads the two-character usage field: {@code R} marks the fix usable enroute and
     * {@code T} usable in the terminal area. A blank field is treated as enroute, which is
     * what the {@code EA} section implies.
     */
    public static WaypointUsage fromArincField(String field) {
        if (field == null) {
            return ENROUTE;
        }
        String f = field.trim().toUpperCase(java.util.Locale.ROOT);
        boolean enroute = f.contains("R") || f.isEmpty();
        boolean terminal = f.contains("T");
        if (enroute && terminal) {
            return BOTH;
        }
        return terminal ? TERMINAL : ENROUTE;
    }

    public boolean allowsEnroute() {
        return this != TERMINAL;
    }
}
