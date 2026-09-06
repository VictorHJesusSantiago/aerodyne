package io.aerodyne.navdata;

/**
 * Whether an airway may be flown in one direction only. ARINC 424 records this per point, in
 * the direction of increasing sequence number.
 */
public enum DirectionRestriction {

    /** No restriction; the leg may be flown either way. */
    BOTH,
    /** Only in the direction of increasing sequence number. */
    FORWARD,
    /** Only in the direction of decreasing sequence number. */
    BACKWARD;

    /** Reads the one-character direction restriction field: {@code F}, {@code B}, or blank. */
    public static DirectionRestriction fromArincField(String field) {
        if (field == null || field.isBlank()) {
            return BOTH;
        }
        return switch (Character.toUpperCase(field.trim().charAt(0))) {
            case 'F' -> FORWARD;
            case 'B' -> BACKWARD;
            default -> BOTH;
        };
    }

    public boolean allowsForward() {
        return this != BACKWARD;
    }

    public boolean allowsBackward() {
        return this != FORWARD;
    }
}
