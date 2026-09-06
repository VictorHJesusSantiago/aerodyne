package io.aerodyne.navdata;

/**
 * The level band an airway serves. The distinction matters when filing: a low-level airway is
 * not available at cruise altitudes and the route search must not offer it.
 */
public enum AirwayLevel {

    /** Upper airspace only. */
    HIGH,
    /** Lower airspace only. */
    LOW,
    /** Published for both bands. */
    ALL;

    /** Reads the one-character level field: {@code H}, {@code L}, or {@code B}/blank for both. */
    public static AirwayLevel fromArincField(String field) {
        if (field == null || field.isBlank()) {
            return ALL;
        }
        return switch (Character.toUpperCase(field.trim().charAt(0))) {
            case 'H' -> HIGH;
            case 'L' -> LOW;
            default -> ALL;
        };
    }

    public boolean servesAltitude(double altitudeFt) {
        return switch (this) {
            case HIGH -> altitudeFt >= 19_500.0;
            case LOW -> altitudeFt < 19_500.0;
            case ALL -> true;
        };
    }
}
