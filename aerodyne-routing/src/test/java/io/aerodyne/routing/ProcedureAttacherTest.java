package io.aerodyne.routing;

import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.NavigationDatabase;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import io.aerodyne.navdata.procedure.AltitudeConstraint;
import io.aerodyne.navdata.procedure.LegType;
import io.aerodyne.navdata.procedure.Procedure;
import io.aerodyne.navdata.procedure.ProcedureLeg;
import io.aerodyne.navdata.procedure.ProcedureTransition;
import io.aerodyne.navdata.procedure.ProcedureType;
import io.aerodyne.navdata.procedure.SpeedConstraint;
import io.aerodyne.navdata.procedure.TransitionKind;
import io.aerodyne.navdata.procedure.TurnDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Joining terminal procedures onto an enroute route.
 *
 * <p>The network is the one the other routing tests use, with a departure off EGXX that ends at
 * ALFA — the first enroute point — and an arrival into EGYY that begins at CHARL, the last. So a
 * procedure that fits and one that does not are both available to test against.
 */
class ProcedureAttacherTest {

    private static final GeoPoint DEPARTURE_FIX = GeoPoint.of(51.35, -0.9);
    private static final GeoPoint ARRIVAL_FIX = GeoPoint.of(48.8, -15.2);

    private final NavigationDatabase database = build();
    private final ProcedureAttacher attacher = new ProcedureAttacher(database);
    private final RouteExpander expander = new RouteExpander(database);

    private Airport airport(String icao) {
        return database.airport(icao).orElseThrow();
    }

    private ExpandedRoute enrouteRoute() {
        return expander.expand("EGXX ALFA UM1 CHARL EGYY", TestNetwork.EGXX);
    }

    // ------------------------------------------------------------------ the network

    private static NavigationDatabase build() {
        NavigationDatabase base = TestNetwork.build();
        return NavigationDatabase.builder()
                .cycle(new AiracCycle(2024, 13))
                .airports(List.of(withRunway("EGXX", TestNetwork.EGXX),
                        withRunway("EGYY", TestNetwork.EGYY)))
                .waypoints(List.of(
                        Waypoint.enroute("ALFA", "EG", TestNetwork.ALFA),
                        Waypoint.enroute("BRAVO", "EG", TestNetwork.BRAVO),
                        Waypoint.enroute("CHARL", "EG", TestNetwork.CHARL),
                        Waypoint.enroute("DELTA", "EG", TestNetwork.DELTA),
                        Waypoint.enroute("ECHO", "EG", TestNetwork.ECHO),
                        Waypoint.enroute("NORTH", "EG", TestNetwork.NORTH),
                        Waypoint.enroute("DEPFX", "EG", DEPARTURE_FIX),
                        Waypoint.enroute("ARRFX", "EG", ARRIVAL_FIX)))
                .airways(base.airways())
                .procedures(List.of(departure(), departureOffAnotherRunway(), arrival()))
                .build();
    }

    private static Airport withRunway(String icao, GeoPoint position) {
        Runway rw09 = new Runway(icao, "RW09", position, 90.0, 10_000, 150, 100, 0, 0.0, "IXXX");
        Runway rw27 = new Runway(icao, "RW27", position, 270.0, 10_000, 150, 100, 0, 0.0, null);
        return new Airport(icao, null, position, 100, 6000, 10_000, "C", icao,
                List.of(rw09, rw27));
    }

    /** Off runway 09: a climb on runway heading, then two fixes, ending at ALFA. */
    private static Procedure departure() {
        ProcedureLeg climb = new ProcedureLeg(10, LegType.VA, null, null,
                TurnDirection.UNSPECIFIED, null, null, null, null, 90.0, null,
                AltitudeConstraint.of("+", 800, null), SpeedConstraint.NONE, "    ", null);
        ProcedureLeg toDepFix = new ProcedureLeg(20, LegType.DF, "DEPFX", "EG",
                TurnDirection.LEFT, null, null, null, null, null, null,
                AltitudeConstraint.of("+", 3000, null), SpeedConstraint.of(" ", 220), " Y  ", null);
        ProcedureLeg toAlfa = new ProcedureLeg(10, LegType.TF, "ALFA", "EG",
                TurnDirection.UNSPECIFIED, null, null, null, null, 250.0, 40.0,
                AltitudeConstraint.of("+", 6000, null), SpeedConstraint.NONE, "   Y", null);

        return new Procedure("EGXX", "ALFA1A", ProcedureType.SID, '1', List.of(
                new ProcedureTransition(TransitionKind.RUNWAY, "RW09", '1',
                        List.of(climb, toDepFix)),
                new ProcedureTransition(TransitionKind.COMMON, "", '2', List.of(toAlfa))));
    }

    /** Off runway 27 only, and it ends at NORTH — nowhere near this route. */
    private static Procedure departureOffAnotherRunway() {
        ProcedureLeg toNorth = new ProcedureLeg(10, LegType.TF, "NORTH", "EG",
                TurnDirection.UNSPECIFIED, null, null, null, null, 20.0, 90.0,
                AltitudeConstraint.NONE, SpeedConstraint.NONE, "   Y", null);
        return new Procedure("EGXX", "NRTH2B", ProcedureType.SID, '1', List.of(
                new ProcedureTransition(TransitionKind.RUNWAY, "RW27", '1', List.of(toNorth))));
    }

    /** Into EGYY: begins at CHARL, the last enroute point, and runs in to ARRFX. */
    private static Procedure arrival() {
        ProcedureLeg fromCharl = new ProcedureLeg(10, LegType.IF, "CHARL", "EG",
                TurnDirection.UNSPECIFIED, null, null, null, null, null, null,
                AltitudeConstraint.NONE, SpeedConstraint.NONE, "    ", null);
        ProcedureLeg toArrFix = new ProcedureLeg(10, LegType.TF, "ARRFX", "EG",
                TurnDirection.UNSPECIFIED, null, null, null, null, 200.0, 30.0,
                AltitudeConstraint.of("-", 10_000, null), SpeedConstraint.of(" ", 250), "   Y",
                null);
        return new Procedure("EGYY", "CHAR1C", ProcedureType.STAR, '1', List.of(
                new ProcedureTransition(TransitionKind.ENROUTE, "CHARL", '1', List.of(fromCharl)),
                new ProcedureTransition(TransitionKind.COMMON, "", '2', List.of(toArrFix))));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void attaches_a_named_departure_and_arrival() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", "09", "CHAR1C");

        assertThat(result.departure()).contains("ALFA1A");
        assertThat(result.arrival()).contains("CHAR1C");
        assertThat(result.route().elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "DEPFX", "ALFA", "BRAVO", "CHARL", "ARRFX", "EGYY");
    }

    @Test
    void a_leg_that_ends_at_an_altitude_is_left_out_of_the_track_and_reported() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", null, null);

        // The VA climb has no position, so it is not a point of the route.
        assertThat(result.route().elements()).extracting(RouteElement::ident)
                .doesNotContain((String) null);
        assertThat(result.route().warnings())
                .anyMatch(warning -> warning.message().contains("end at an altitude or a heading"));
    }

    @Test
    void the_point_where_the_procedure_meets_the_route_appears_once() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", "09", "CHAR1C");

        assertThat(result.route().elements()).extracting(RouteElement::ident)
                .filteredOn("ALFA"::equals).hasSize(1);
        assertThat(result.route().elements()).extracting(RouteElement::ident)
                .filteredOn("CHARL"::equals).hasSize(1);
    }

    @Test
    void attaching_a_departure_lengthens_the_route() {
        ExpandedRoute plain = enrouteRoute();
        ProcedureAttacher.Result withProcedures = attacher.attach(plain,
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", "09", "CHAR1C");

        assertThat(withProcedures.route().distanceNm()).isGreaterThan(plain.distanceNm());
    }

    @Test
    void chooses_a_departure_that_joins_the_route_when_none_is_named() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", null, "09", null);

        // ALFA1A ends at ALFA, which is where the route begins; NRTH2B does not and is off 27.
        assertThat(result.departure()).contains("ALFA1A");
        assertThat(result.arrival()).contains("CHAR1C");
    }

    @Test
    void a_named_departure_that_is_not_published_for_the_runway_is_refused_with_a_reason() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "NRTH2B", null, null);

        assertThat(result.departure()).isEmpty();
        assertThat(result.route().warnings())
                .anyMatch(warning -> warning.token().equals("NRTH2B")
                        && warning.message().contains("not published for runway 09"));
    }

    @Test
    void a_named_procedure_that_does_not_exist_is_refused_with_a_reason() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "NOSUCH", null, null);

        assertThat(result.departure()).isEmpty();
        assertThat(result.route().warnings())
                .anyMatch(warning -> warning.message().contains("no such procedure"));
    }

    @Test
    void an_aerodrome_with_no_procedures_plans_from_the_aerodrome() {
        NavigationDatabase bare = NavigationDatabase.builder()
                .cycle(new AiracCycle(2024, 13))
                .airports(List.of(withRunway("EGXX", TestNetwork.EGXX),
                        withRunway("EGYY", TestNetwork.EGYY)))
                .waypoints(List.of(Waypoint.enroute("ALFA", "EG", TestNetwork.ALFA)))
                .build();

        ExpandedRoute route = new RouteExpander(bare).expand("EGXX ALFA EGYY", TestNetwork.EGXX);
        ProcedureAttacher.Result result = new ProcedureAttacher(bare).attach(route,
                bare.airport("EGXX").orElseThrow(), bare.airport("EGYY").orElseThrow(),
                "09", null, "09", null);

        assertThat(result.departure()).isEmpty();
        assertThat(result.route().elements()).extracting(RouteElement::ident)
                .containsExactly("EGXX", "ALFA", "EGYY");
    }

    @Test
    void the_route_string_names_the_procedures_rather_than_expanding_them() {
        ProcedureAttacher.Result result = attacher.attach(enrouteRoute(),
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", "09", "CHAR1C");

        assertThat(result.route().routeString())
                .isEqualTo("EGXX ALFA1A ALFA UM1 CHARL CHAR1C EGYY");
    }

    @Test
    void a_route_of_only_two_points_is_returned_unchanged() {
        ExpandedRoute direct = expander.expand("EGXX EGYY", TestNetwork.EGXX);

        ProcedureAttacher.Result result = attacher.attach(direct,
                airport("EGXX"), airport("EGYY"), "09", null, "09", null);

        assertThat(result.route().elements()).hasSize(2);
        assertThat(result.departure()).isEmpty();
    }

    @Test
    void the_warnings_from_the_enroute_route_survive() {
        ExpandedRoute withBadFix = expander.expand("EGXX ALFA ZULU9 UM1 CHARL EGYY",
                TestNetwork.EGXX);

        ProcedureAttacher.Result result = attacher.attach(withBadFix,
                airport("EGXX"), airport("EGYY"), "09", "ALFA1A", null, null);

        assertThat(result.route().warnings())
                .anyMatch(warning -> warning.token().equals("ZULU9"));
    }
}
