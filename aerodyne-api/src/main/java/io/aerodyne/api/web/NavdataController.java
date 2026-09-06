package io.aerodyne.api.web;

import io.aerodyne.api.service.NavigationDataService;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.geo.Geodesy;
import io.aerodyne.navdata.Fix;
import io.aerodyne.navdata.FixKind;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureTransition;
import io.aerodyne.navdata.procedure.ProcedureType;
import io.aerodyne.routing.ExpandedRoute;
import io.aerodyne.routing.RouteExpander;
import io.aerodyne.routing.RouteWarning;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Browsing the navigation database and checking a route against it.
 *
 * <p>The route endpoint is the useful one: it answers "would this route resolve, and what would
 * it actually fly" without planning anything. That is the question a dispatcher asks a dozen
 * times before committing to a plan, and answering it needs no aircraft, no weather and no fuel.
 */
@RestController
@RequestMapping("/api/navdata")
public class NavdataController {

    /** How far a fix search will reach by default. */
    private static final double DEFAULT_RADIUS_NM = 100.0;
    /** The most fixes a search will return, so a wide radius cannot return the whole cycle. */
    private static final int MAX_RESULTS = 200;

    private final NavigationDataService navigation;

    public NavdataController(NavigationDataService navigation) {
        this.navigation = navigation;
    }

    /** Which cycle is loaded, and how much is in it. */
    @GetMapping("/cycle")
    public CycleSummary cycle() {
        NavigationDatabase database = navigation.database();
        return new CycleSummary(
                database.cycle().map(c -> c.identifier()).orElse(null),
                database.cycle().map(c -> c.effectiveDate().toString()).orElse(null),
                database.cycle().map(c -> c.expiryDate().toString()).orElse(null),
                database.fixCount(), database.airportCount(), database.airwayCount());
    }

    /** Everything published under an identifier — which may be several things. */
    @GetMapping("/fixes/{ident}")
    public List<FixSummary> fixes(@PathVariable String ident) {
        return navigation.database().allNamed(ident).stream()
                .map(fix -> FixSummary.of(fix, null))
                .toList();
    }

    /** The fixes near a position, nearest first. */
    @GetMapping("/fixes")
    public List<FixSummary> near(@RequestParam double latitude,
                                 @RequestParam double longitude,
                                 @RequestParam(required = false) Double radiusNm) {
        GeoPoint centre = GeoPoint.of(latitude, longitude);
        double radius = radiusNm == null ? DEFAULT_RADIUS_NM : radiusNm;
        return navigation.database().fixesWithin(centre, radius, MAX_RESULTS).stream()
                .map(fix -> FixSummary.of(fix, centre))
                .toList();
    }

    /** An airport and its runways. */
    @GetMapping("/airports/{icao}")
    public AirportSummary airport(@PathVariable String icao) {
        return navigation.database().airport(icao)
                .map(airport -> new AirportSummary(airport.icaoCode(), airport.iataCode(),
                        airport.name(), airport.position().latitude(), airport.position().longitude(),
                        airport.elevationFt(), airport.longestRunwayFt(),
                        airport.hasPrecisionApproach(),
                        airport.runways().stream()
                                .map(runway -> new RunwaySummary(runway.shortDesignator(),
                                        runway.lengthFt(), runway.widthFt(),
                                        runway.magneticBearingDeg(), runway.ilsIdent()))
                                .toList()))
                .orElseThrow(() -> new ApiExceptionHandler.NotFoundException("airport", icao));
    }

    /**
     * The procedures published for an aerodrome, optionally narrowed to one kind and one runway.
     *
     * <p>The legs come back as they would be flown, with the runway and enroute segments joined
     * onto the common route. That is what a crew reads, and it is not how the data is stored:
     * the segments are kept apart so that the shared middle is stored once.
     */
    @GetMapping("/airports/{icao}/procedures")
    public List<ProcedureSummary> procedures(@PathVariable String icao,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String runway) {
        NavigationDatabase database = navigation.database();
        List<Procedure> found = type == null
                ? database.procedures(icao)
                : database.procedures(icao, procedureType(type));

        return found.stream()
                .filter(procedure -> runway == null || procedure.servesRunway(runway))
                .map(procedure -> summarise(procedure, runway))
                .toList();
    }

    private static ProcedureType procedureType(String type) {
        try {
            return ProcedureType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "'" + type + "' is not a procedure type; use SID, STAR or APPROACH");
        }
    }

    private static ProcedureSummary summarise(Procedure procedure, String runway) {
        return new ProcedureSummary(
                procedure.ident(),
                procedure.type().name(),
                procedure.approachKind(),
                procedure.runways(),
                procedure.enrouteTransitions().stream()
                        .map(ProcedureTransition::identifier).toList(),
                procedure.isFullyResolvable(runway, null),
                procedure.assemble(runway, null).stream()
                        .map(leg -> new ProcedureLegSummary(
                                leg.legType().code(), leg.fixIdent(),
                                leg.altitude().toChartString(),
                                leg.speed().isConstrained() ? leg.speed().speedKt() : null,
                                leg.isFlyOver(), leg.toChartString()))
                        .toList());
    }

    /**
     * Expands a route without planning against it: what it resolves to, how long it is, and
     * anything in it that could not be honoured.
     */
    @GetMapping("/route")
    public RouteSummary route(@RequestParam String route,
                              @RequestParam(required = false) String from) {
        NavigationDatabase database = navigation.database();
        GeoPoint start = from == null
                ? null
                : database.airport(from).map(a -> a.position()).orElse(null);

        ExpandedRoute expanded = new RouteExpander(database).expand(route, start);
        return new RouteSummary(
                expanded.toRouteString(),
                Math.round(expanded.distanceNm() * 10) / 10.0,
                Math.round(expanded.excessOverDirectNm() * 10) / 10.0,
                expanded.airways(),
                expanded.elements().stream()
                        .map(element -> new RoutePoint(element.ident(),
                                element.position().latitude(), element.position().longitude(),
                                element.viaAirway(), element.implied()))
                        .toList(),
                expanded.warnings().stream().map(RouteWarning::toString).toList(),
                expanded.isComplete());
    }

    /** What is loaded. */
    public record CycleSummary(String identifier, String effectiveDate, String expiryDate,
                               int fixes, int airports, int airways) {
    }

    /** One fix, with the distance from the search centre when there was one. */
    public record FixSummary(String ident, FixKind kind, String icaoRegion,
                             double latitude, double longitude, Double distanceNm) {

        static FixSummary of(Fix fix, GeoPoint from) {
            return new FixSummary(fix.ident(), fix.kind(), fix.icaoRegion(),
                    fix.position().latitude(), fix.position().longitude(),
                    from == null ? null
                            : Math.round(Geodesy.distanceNm(from, fix.position()) * 10) / 10.0);
        }
    }

    /** An airport, as the browse endpoint reports it. */
    public record AirportSummary(String icaoCode, String iataCode, String name,
                                 double latitude, double longitude, int elevationFt,
                                 int longestRunwayFt, boolean precisionApproach,
                                 List<RunwaySummary> runways) {
    }

    /** One runway. */
    public record RunwaySummary(String designator, int lengthFt, int widthFt,
                                double magneticBearingDeg, String ilsIdent) {
    }

    /** A procedure, with its legs as they would be flown. */
    public record ProcedureSummary(String ident, String type, String kind, List<String> runways,
                                   List<String> transitions, boolean fullyResolvable,
                                   List<ProcedureLegSummary> legs) {
    }

    /** One leg of it. */
    public record ProcedureLegSummary(String legType, String fix, String altitude,
                                      Integer speedKt, boolean flyOver, String chart) {
    }

    /** An expanded route. */
    public record RouteSummary(String route, double distanceNm, double excessOverDirectNm,
                               List<String> airways, List<RoutePoint> points,
                               List<String> warnings, boolean complete) {
    }

    /** One point of it. */
    public record RoutePoint(String ident, double latitude, double longitude,
                             String viaAirway, boolean implied) {
    }
}
