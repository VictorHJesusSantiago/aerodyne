package io.aerodyne.dispatch;

import io.aerodyne.dispatch.notam.Notam;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.weather.metar.SurfaceConditions;
import io.aerodyne.weather.metar.TafReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chooses alternates.
 *
 * <p>Every aerodrome within the search radius is screened against the criteria, and the ones
 * that survive are ranked by distance. Four things can rule one out, and each is checked
 * against the right source:
 *
 * <ul>
 *   <li><b>Runway</b> — from the navigation database.</li>
 *   <li><b>Approach</b> — whether the aerodrome has a precision approach, when one is required.</li>
 *   <li><b>Weather</b> — from the TAF, read at the expected arrival time, and read as its
 *       <em>worst case</em> rather than its prevailing conditions. A forecast that admits to a
 *       temporary deterioration below minima is a forecast that may deliver one, and holding an
 *       alternate against the better half of it would defeat the point of holding one.</li>
 *   <li><b>NOTAMs</b> — an aerodrome closed at the arrival time is not an alternate however
 *       good the weather is.</li>
 * </ul>
 *
 * <p>An aerodrome with no forecast at all is rejected when the criteria demand current weather.
 * That is the conservative reading and the right one: dispatch cannot sign for an alternate on
 * the strength of a forecast nobody has.
 */
public final class AlternateSelector {

    private final NavigationDatabase database;

    public AlternateSelector(NavigationDatabase database) {
        this.database = database;
    }

    /**
     * Screens every aerodrome near a position, usable ones first and nearest first within that.
     *
     * @param near        the position to search around, usually the destination
     * @param criteria    what an alternate has to satisfy
     * @param forecasts   the TAFs available, by ICAO code
     * @param notams      the NOTAMs to check against
     * @param arrivalTime when the flight would arrive at the alternate
     */
    public List<AlternateCandidate> screen(GeoPoint near,
                                           AlternateCriteria criteria,
                                           Map<String, TafReport> forecasts,
                                           List<Notam> notams,
                                           Instant arrivalTime) {
        List<AlternateCandidate> candidates = new ArrayList<>();
        for (Airport airport : database.airportsWithin(near, criteria.maxDistanceNm())) {
            candidates.add(assess(airport, near, criteria, forecasts, notams, arrivalTime));
        }
        candidates.sort(Comparator
                .comparing((AlternateCandidate c) -> !c.isUsable())
                .thenComparingDouble(AlternateCandidate::distanceNm));
        return List.copyOf(candidates);
    }

    /**
     * The usable alternates only, nearest first. This is what goes on the flight plan; the full
     * screening is what explains it.
     */
    public List<AlternateCandidate> select(GeoPoint near,
                                           AlternateCriteria criteria,
                                           Map<String, TafReport> forecasts,
                                           List<Notam> notams,
                                           Instant arrivalTime) {
        return screen(near, criteria, forecasts, notams, arrivalTime).stream()
                .filter(AlternateCandidate::isUsable)
                .toList();
    }

    /**
     * The best alternate, or empty when nothing near the destination qualifies — which is a
     * result, not a failure: it means the flight needs a different destination, more fuel, or a
     * wider search.
     */
    public Optional<AlternateCandidate> best(GeoPoint near,
                                             AlternateCriteria criteria,
                                             Map<String, TafReport> forecasts,
                                             List<Notam> notams,
                                             Instant arrivalTime) {
        return select(near, criteria, forecasts, notams, arrivalTime).stream().findFirst();
    }

    /** Assesses one aerodrome, collecting every reason it cannot be used rather than the first. */
    private AlternateCandidate assess(Airport airport, GeoPoint near, AlternateCriteria criteria,
                                      Map<String, TafReport> forecasts, List<Notam> notams,
                                      Instant arrivalTime) {
        List<String> rejections = new ArrayList<>();
        double distance = Geodesy.distanceNm(near, airport.position());
        double bearing = Geodesy.initialBearingDeg(near, airport.position());

        if (!airport.hasRunwayAtLeast(criteria.minimumRunwayFt())) {
            rejections.add("no runway of " + criteria.minimumRunwayFt() + " ft");
        }
        if (criteria.requirePrecisionApproach() && !airport.hasPrecisionApproach()) {
            rejections.add("no precision approach");
        }

        SurfaceConditions conditions = worstCaseAt(forecasts.get(airport.icaoCode()), arrivalTime)
                .orElse(null);
        if (conditions == null) {
            if (criteria.requireCurrentWeather()) {
                rejections.add("no forecast covering the arrival time");
            }
        } else if (!conditions.meets(criteria.requiredCeilingFt(), criteria.requiredVisibilityMetres())) {
            rejections.add(describeWeatherShortfall(conditions, criteria));
        }

        notams.stream()
                .filter(notam -> notam.appliesTo(airport.icaoCode()))
                .filter(notam -> notam.isActiveAt(arrivalTime))
                .filter(Notam::closesAerodrome)
                .findFirst()
                .ifPresent(notam -> rejections.add("closed by " + notam.identifier()));

        return new AlternateCandidate(airport, distance, bearing, conditions, rejections);
    }

    /** The forecast's worst case at the arrival time, which is what an alternate is held against. */
    private static Optional<SurfaceConditions> worstCaseAt(TafReport forecast, Instant arrivalTime) {
        return forecast == null ? Optional.empty() : forecast.worstCaseAt(arrivalTime);
    }

    /** Says which half of the minima the forecast failed, since they are held to separately. */
    private static String describeWeatherShortfall(SurfaceConditions conditions,
                                                   AlternateCriteria criteria) {
        java.util.OptionalInt visibility = conditions.visibility();
        if (visibility.isEmpty()) {
            return "forecast gives no visibility";
        }
        if (visibility.getAsInt() < criteria.requiredVisibilityMetres()) {
            return "forecast visibility " + visibility.getAsInt() + " m is below the required "
                    + criteria.requiredVisibilityMetres() + " m";
        }
        java.util.OptionalInt ceiling = conditions.ceilingFt();
        if (ceiling.isPresent() && ceiling.getAsInt() < criteria.requiredCeilingFt()) {
            return "forecast ceiling " + ceiling.getAsInt() + " ft is below the required "
                    + criteria.requiredCeilingFt() + " ft";
        }
        return "forecast is below the planning minima";
    }
}
