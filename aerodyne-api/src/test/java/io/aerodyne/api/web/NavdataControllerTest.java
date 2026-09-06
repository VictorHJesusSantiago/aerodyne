package io.aerodyne.api.web;

import io.aerodyne.api.service.NavigationDataService;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The navdata endpoints, driven through MockMvc.
 *
 * <p>Built with {@code standaloneSetup} rather than {@code @WebMvcTest}: the controller's only
 * collaborator is one service, which a three-line stub replaces, and a standalone setup starts
 * in milliseconds without a Spring context or a database behind it.
 */
class NavdataControllerTest {

    private MockMvc mvc;

    private static final GeoPoint EGLL = GeoPoint.of(51.4775, -0.4614);
    private static final GeoPoint OCK = GeoPoint.of(51.3050, -0.4472);
    private static final GeoPoint KOLIN = GeoPoint.of(51.0, -2.0);
    private static final GeoPoint BEDEK = GeoPoint.of(50.5, -4.0);

    @BeforeEach
    void setUp() {
        NavigationDatabase database = NavigationDatabase.builder()
                .cycle(new AiracCycle(2024, 13))
                .airports(List.of(heathrow()))
                .waypoints(List.of(
                        Waypoint.enroute("OCK", "EG", OCK),
                        Waypoint.enroute("KOLIN", "EG", KOLIN),
                        Waypoint.enroute("BEDEK", "EG", BEDEK),
                        Waypoint.enroute("KOLIN", "SB", GeoPoint.of(-23.0, -46.0))))
                .airways(List.of(airway("UN862", "OCK", "KOLIN", "BEDEK")))
                .procedures(List.of(departure()))
                .build();

        NavdataController controller = new NavdataController(new StubNavigationData(database));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Airport heathrow() {
        Runway rw27l = new Runway("EGLL", "RW27L", EGLL, 269.5, 12_799, 164, 79, 0, 0.0, "ILL");
        Runway rw09r = new Runway("EGLL", "RW09R", EGLL, 89.5, 12_799, 164, 77, 306, 0.0, null);
        return new Airport("EGLL", "LHR", EGLL, 83, 6000, 12_799, "C",
                "LONDON HEATHROW", List.of(rw27l, rw09r));
    }

    /** A departure off 27L: a climb to an altitude, then a fix. */
    private static Procedure departure() {
        ProcedureLeg climb = new ProcedureLeg(10, LegType.VA, null, null,
                TurnDirection.UNSPECIFIED, null, null, null, null, 269.5, null,
                AltitudeConstraint.of("+", 800, null), SpeedConstraint.NONE, "    ", null);
        ProcedureLeg toOck = new ProcedureLeg(20, LegType.TF, "OCK", "EG",
                TurnDirection.LEFT, null, null, null, null, 210.0, 12.0,
                AltitudeConstraint.of("+", 6000, null), SpeedConstraint.of(" ", 220), " Y Y",
                null);
        return new Procedure("EGLL", "OCKH1A", ProcedureType.SID, '1', List.of(
                new ProcedureTransition(TransitionKind.RUNWAY, "RW27L", '1',
                        List.of(climb, toOck))));
    }

    private static Airway airway(String ident, String... fixes) {
        List<AirwayPoint> points = new ArrayList<>();
        for (int i = 0; i < fixes.length; i++) {
            points.add(new AirwayPoint((i + 1) * 10, fixes[i], "EG", DirectionRestriction.BOTH,
                    AirwayLevel.ALL, 5_000, 46_000, i == fixes.length - 1 ? " EE " : "    "));
        }
        return new Airway(ident, points);
    }

    @Test
    void reports_the_cycle_that_is_loaded() throws Exception {
        mvc.perform(get("/api/navdata/cycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").value("2413"))
                .andExpect(jsonPath("$.airports").value(1))
                .andExpect(jsonPath("$.airways").value(1))
                .andExpect(jsonPath("$.fixes").value(7));
    }

    @Test
    void returns_every_fix_published_under_an_identifier() throws Exception {
        mvc.perform(get("/api/navdata/fixes/KOLIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].icaoRegion").value(org.hamcrest.Matchers.hasItems("EG", "SB")));
    }

    @Test
    void an_unknown_identifier_returns_an_empty_list_rather_than_a_404() throws Exception {
        mvc.perform(get("/api/navdata/fixes/ZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void finds_the_fixes_near_a_position_nearest_first_with_their_distances() throws Exception {
        mvc.perform(get("/api/navdata/fixes")
                        .param("latitude", "51.4")
                        .param("longitude", "-0.45")
                        .param("radiusNm", "60"))
                .andExpect(status().isOk())
                // The airport reference point is the nearest thing to the query. Its runway
                // thresholds are fixes too, and they sit between it and the waypoint.
                .andExpect(jsonPath("$[0].ident").value("EGLL"))
                .andExpect(jsonPath("$[0].distanceNm").isNumber())
                .andExpect(jsonPath("$[*].ident").value(
                        org.hamcrest.Matchers.hasItems("EGLL", "RW27L", "OCK")));
    }

    @Test
    void returns_an_airport_with_its_runways() throws Exception {
        mvc.perform(get("/api/navdata/airports/EGLL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icaoCode").value("EGLL"))
                .andExpect(jsonPath("$.iataCode").value("LHR"))
                .andExpect(jsonPath("$.precisionApproach").value(true))
                .andExpect(jsonPath("$.runways.length()").value(2))
                // Runways come back in the order the navigation data published them.
                .andExpect(jsonPath("$.runways[0].designator").value("27L"))
                .andExpect(jsonPath("$.runways[1].designator").value("09R"));
    }

    @Test
    void an_airport_that_is_not_in_the_cycle_is_a_404() throws Exception {
        mvc.perform(get("/api/navdata/airports/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("ZZZZ")));
    }

    @Test
    void lists_the_procedures_published_for_an_aerodrome() throws Exception {
        mvc.perform(get("/api/navdata/airports/{icao}/procedures", "EGLL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ident").value("OCKH1A"))
                .andExpect(jsonPath("$[0].type").value("SID"))
                .andExpect(jsonPath("$[0].runways[0]").value("RW27L"))
                // A climb to an altitude has no position, so the procedure is not fully drawable.
                .andExpect(jsonPath("$[0].fullyResolvable").value(false))
                .andExpect(jsonPath("$[0].legs.length()").value(2))
                .andExpect(jsonPath("$[0].legs[0].legType").value("VA"))
                .andExpect(jsonPath("$[0].legs[1].fix").value("OCK"))
                .andExpect(jsonPath("$[0].legs[1].speedKt").value(220))
                .andExpect(jsonPath("$[0].legs[1].flyOver").value(true))
                .andExpect(jsonPath("$[0].legs[1].chart").value(
                        org.hamcrest.Matchers.containsString("TF OCK 6000A")));
    }

    @Test
    void narrows_the_procedures_by_kind_and_by_runway() throws Exception {
        mvc.perform(get("/api/navdata/airports/{icao}/procedures", "EGLL")
                        .param("type", "sid").param("runway", "27L"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/navdata/airports/{icao}/procedures", "EGLL")
                        .param("type", "STAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/navdata/airports/{icao}/procedures", "EGLL")
                        .param("runway", "09R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void a_procedure_type_that_is_not_one_is_a_400_saying_what_they_are() throws Exception {
        mvc.perform(get("/api/navdata/airports/{icao}/procedures", "EGLL")
                        .param("type", "APPROACHES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("use SID, STAR or APPROACH")));
    }

    @Test
    void expands_a_route_and_reports_what_it_flies() throws Exception {
        mvc.perform(get("/api/navdata/route")
                        .param("route", "EGLL OCK UN862 BEDEK")
                        .param("from", "EGLL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.airways[0]").value("UN862"))
                .andExpect(jsonPath("$.points.length()").value(4))
                .andExpect(jsonPath("$.points[2].ident").value("KOLIN"))
                .andExpect(jsonPath("$.points[2].implied").value(true))
                .andExpect(jsonPath("$.distanceNm").isNumber());
    }

    @Test
    void a_route_naming_something_unknown_comes_back_with_a_warning_rather_than_an_error()
            throws Exception {
        mvc.perform(get("/api/navdata/route")
                        .param("route", "EGLL OCK ZULU9 BEDEK")
                        .param("from", "EGLL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value(
                        org.hamcrest.Matchers.containsString("ZULU9")));
    }

    @Test
    void reports_that_nothing_is_loaded_as_a_503_rather_than_a_500() throws Exception {
        NavdataController controller = new NavdataController(new StubNavigationData(null));
        MockMvc empty = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        empty.perform(get("/api/navdata/cycle"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("No navigation data is loaded"));
    }

    /** A navigation service holding one fixed database, or none. */
    private static final class StubNavigationData extends NavigationDataService {

        private final NavigationDatabase database;

        StubNavigationData(NavigationDatabase database) {
            super(null, null, null, null, null, null);
            this.database = database;
        }

        @Override
        public NavigationDatabase database() {
            if (database == null) {
                throw new IllegalStateException(
                        "no navigation data is loaded; import an AIRAC cycle before planning");
            }
            return database;
        }
    }
}
