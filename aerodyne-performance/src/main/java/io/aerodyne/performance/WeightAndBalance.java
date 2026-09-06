package io.aerodyne.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Computes a loadsheet: the aircraft's weight and centre of gravity at zero fuel, at takeoff and
 * at landing, and whether each is within limits.
 *
 * <p>The arithmetic is moments about the aircraft's reference datum. Every item of load
 * contributes its mass times its arm; the sum of moments divided by the sum of masses is where
 * the centre of gravity sits, which is then expressed as a percentage of the mean aerodynamic
 * chord.
 *
 * <p>Fuel is modelled as a single station with a fixed arm. Real aircraft do better than this:
 * fuel moves between tanks as it burns and the manufacturer publishes an index correction for
 * the resulting shift. Fixing the arm makes the landing centre of gravity approximate, and it
 * is approximate in a knowable direction — so the landing figure here is a planning value, and
 * the departure figures, which do not depend on the assumption, are not.
 */
public final class WeightAndBalance {

    private final AircraftType type;
    private final List<LoadStation> payload = new ArrayList<>();
    private double takeoffFuelKg;
    private double fuelArmMetres;
    private double tripFuelKg;

    private WeightAndBalance(AircraftType type) {
        this.type = type;
        this.fuelArmMetres = type.operatingEmptyArmMetres();
    }

    public static WeightAndBalance forType(AircraftType type) {
        return new WeightAndBalance(type);
    }

    /** Adds one item of load: a cabin zone, a hold, a crew position. */
    public WeightAndBalance load(String name, double massKg, double armMetres) {
        payload.add(new LoadStation(name, massKg, armMetres));
        return this;
    }

    public WeightAndBalance load(LoadStation station) {
        payload.add(station);
        return this;
    }

    public WeightAndBalance load(List<LoadStation> stations) {
        payload.addAll(stations);
        return this;
    }

    /** The fuel on board at takeoff, and where its centre of gravity sits. */
    public WeightAndBalance fuel(double takeoffFuelKg, double armMetres) {
        this.takeoffFuelKg = takeoffFuelKg;
        this.fuelArmMetres = armMetres;
        return this;
    }

    /** The fuel expected to burn between takeoff and landing. */
    public WeightAndBalance tripFuel(double tripFuelKg) {
        this.tripFuelKg = tripFuelKg;
        return this;
    }

    /** Computes the sheet and checks it against every limit. */
    public Loadsheet compute() {
        WeightLimits limits = type.weightLimits();

        List<LoadStation> stations = new ArrayList<>();
        stations.add(new LoadStation("Operating empty weight",
                limits.operatingEmptyKg(), type.operatingEmptyArmMetres()));
        stations.addAll(payload);

        double zeroFuelWeight = stations.stream().mapToDouble(LoadStation::massKg).sum();
        double zeroFuelMoment = stations.stream().mapToDouble(LoadStation::momentKgM).sum();

        stations.add(new LoadStation("Fuel", takeoffFuelKg, fuelArmMetres));
        double takeoffWeight = zeroFuelWeight + takeoffFuelKg;
        double takeoffMoment = zeroFuelMoment + takeoffFuelKg * fuelArmMetres;

        double landingFuel = Math.max(0.0, takeoffFuelKg - tripFuelKg);
        double landingWeight = zeroFuelWeight + landingFuel;
        double landingMoment = zeroFuelMoment + landingFuel * fuelArmMetres;

        double zeroFuelCg = type.armToPercentMac(centreOfGravity(zeroFuelMoment, zeroFuelWeight));
        double takeoffCg = type.armToPercentMac(centreOfGravity(takeoffMoment, takeoffWeight));
        double landingCg = type.armToPercentMac(centreOfGravity(landingMoment, landingWeight));

        List<String> exceedances = checkLimits(limits, zeroFuelWeight, takeoffWeight,
                landingWeight, zeroFuelCg, takeoffCg, landingCg);

        return new Loadsheet(type, stations, zeroFuelWeight, takeoffWeight, landingWeight,
                zeroFuelCg, takeoffCg, landingCg, exceedances);
    }

    private List<String> checkLimits(WeightLimits limits,
                                     double zeroFuelWeight, double takeoffWeight, double landingWeight,
                                     double zeroFuelCg, double takeoffCg, double landingCg) {
        List<String> exceedances = new ArrayList<>();

        if (takeoffFuelKg > limits.maxFuelKg()) {
            exceedances.add(over("fuel on board", takeoffFuelKg, limits.maxFuelKg(), "kg"));
        }
        if (zeroFuelWeight > limits.maxZeroFuelKg()) {
            exceedances.add(over("zero-fuel weight", zeroFuelWeight, limits.maxZeroFuelKg(), "kg"));
        }
        if (takeoffWeight > limits.maxTakeoffKg()) {
            exceedances.add(over("takeoff weight", takeoffWeight, limits.maxTakeoffKg(), "kg"));
        }
        if (landingWeight > limits.maxLandingKg()) {
            exceedances.add(over("landing weight", landingWeight, limits.maxLandingKg(), "kg"));
        }

        checkCg(exceedances, "zero-fuel", zeroFuelWeight, zeroFuelCg);
        checkCg(exceedances, "takeoff", takeoffWeight, takeoffCg);
        checkCg(exceedances, "landing", landingWeight, landingCg);
        return exceedances;
    }

    private void checkCg(List<String> exceedances, String phase, double weightKg, double cgPercentMac) {
        if (type.cgEnvelope().contains(weightKg, cgPercentMac)) {
            return;
        }
        String limits = type.cgEnvelope().limitsAt(weightKg)
                .map(l -> String.format(Locale.ROOT, " (limits %.1f%% to %.1f%% MAC)",
                        l.forwardPercentMac(), l.aftPercentMac()))
                .orElse(" (the weight itself is outside the envelope)");
        exceedances.add(String.format(Locale.ROOT,
                "%s centre of gravity %.1f%% MAC is outside the envelope%s", phase, cgPercentMac, limits));
    }

    private static String over(String what, double actual, double limit, String unit) {
        return String.format(Locale.ROOT, "%s %.0f %s exceeds the limit of %.0f %s by %.0f",
                what, actual, unit, limit, unit, actual - limit);
    }

    /** A zero-weight aircraft has no centre of gravity; the datum is the least wrong answer. */
    private static double centreOfGravity(double momentKgM, double massKg) {
        return massKg <= 0.0 ? 0.0 : momentKgM / massKg;
    }
}
