package io.aerodyne.weather.grib;

/**
 * The handful of GRIB2 parameters flight planning needs, identified by the triple that names
 * one in the WMO tables: the discipline from section 0, and the category and number from the
 * product definition in section 4.
 *
 * <p>A file holds far more than these — humidity, geopotential height, vertical velocity — and
 * those messages are decoded as far as their headers and then skipped.
 */
public enum Grib2Parameter {

    /** {@code UGRD}: the eastward component of the wind, in metres per second. */
    U_WIND(0, 2, 2),
    /** {@code VGRD}: the northward component of the wind, in metres per second. */
    V_WIND(0, 2, 3),
    /** {@code TMP}: temperature, in kelvin. */
    TEMPERATURE(0, 0, 0),
    /** Anything else in the file. */
    OTHER(-1, -1, -1);

    private final int discipline;
    private final int category;
    private final int number;

    Grib2Parameter(int discipline, int category, int number) {
        this.discipline = discipline;
        this.category = category;
        this.number = number;
    }

    public static Grib2Parameter of(int discipline, int category, int number) {
        for (Grib2Parameter parameter : values()) {
            if (parameter != OTHER && parameter.discipline == discipline
                    && parameter.category == category && parameter.number == number) {
                return parameter;
            }
        }
        return OTHER;
    }

    /** True for the fields the upper-air model is built from. */
    public boolean isUpperAir() {
        return this != OTHER;
    }
}
