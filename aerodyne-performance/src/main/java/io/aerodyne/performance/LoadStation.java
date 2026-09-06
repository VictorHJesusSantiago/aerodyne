package io.aerodyne.performance;

/**
 * One item of load and where it sits.
 *
 * @param name       what it is — a cabin zone, a hold, a fuel tank
 * @param massKg     how much it weighs
 * @param armMetres  the distance of its centre of gravity from the aircraft's reference datum,
 *                   positive aft
 */
public record LoadStation(String name, double massKg, double armMetres) {

    public LoadStation {
        if (massKg < 0) {
            throw new IllegalArgumentException("station " + name + " has a negative mass");
        }
    }

    /**
     * The station's moment about the datum, in kilogram-metres. Summing moments and dividing by
     * the total mass is what locates the loaded centre of gravity.
     */
    public double momentKgM() {
        return massKg * armMetres;
    }
}
