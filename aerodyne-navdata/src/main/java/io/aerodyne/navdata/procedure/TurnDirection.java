package io.aerodyne.navdata.procedure;

/** Which way a procedure leg turns, when the procedure specifies one. */
public enum TurnDirection {

    /** {@code L}: left turn. */
    LEFT,
    /** {@code R}: right turn. */
    RIGHT,
    /** {@code E}: either, at the crew's discretion. */
    EITHER,
    /** No turn direction is published; the shorter way round is flown. */
    UNSPECIFIED;

    /** Reads the one-character turn direction field. */
    public static TurnDirection fromCode(String field) {
        if (field == null || field.isBlank()) {
            return UNSPECIFIED;
        }
        return switch (Character.toUpperCase(field.trim().charAt(0))) {
            case 'L' -> LEFT;
            case 'R' -> RIGHT;
            case 'E' -> EITHER;
            default -> UNSPECIFIED;
        };
    }

    /** True when the procedure requires a particular direction rather than leaving it open. */
    public boolean isMandatory() {
        return this == LEFT || this == RIGHT;
    }
}
