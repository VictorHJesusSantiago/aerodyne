package io.aerodyne.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerodyne.api.persistence.AirportRepository;
import io.aerodyne.api.persistence.AirwayRepository;
import io.aerodyne.api.persistence.CycleRepository;
import io.aerodyne.api.persistence.DatabaseFixture;
import io.aerodyne.api.persistence.FlightPlanRepository;
import io.aerodyne.api.persistence.NavigationEntities;
import io.aerodyne.api.persistence.WaypointRepository;
import io.aerodyne.api.service.NavigationDataService;
import io.aerodyne.geo.GeoPoint;
import io.aerodyne.navdata.AiracCycle;
import io.aerodyne.navdata.Airport;
import io.aerodyne.navdata.Airway;
import io.aerodyne.navdata.AirwayLevel;
import io.aerodyne.navdata.AirwayPoint;
import io.aerodyne.navdata.DirectionRestriction;
import io.aerodyne.navdata.Runway;
import io.aerodyne.navdata.Waypoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planning a flight through the API, end to end and against a real database.
 *
 * <p>The other tests each cover one layer with the ones underneath stubbed. This covers the
 * seam they cannot: a request arrives, the engine plans against navigation data read out of
 * PostGIS, the plan is written back, and the stored OFP comes out of a second request. Every
 * one of those steps works in isolation; whether they work together is a different question,
 * and it is the one a deployment actually depends on.
 *
 * <p>Needs a database, and skips itself cleanly without one — see {@link DatabaseFixture}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("databaseIsAvailable")
class PlanningControllerIntegrationTest {

    static boolean databaseIsAvailable() {
        return DatabaseFixture.isAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        DatabaseFixture.configure(registry);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private CycleRepository cycles;
    @Autowired
    private AirportRepository airports;
    @Autowired
    private WaypointRepository waypoints;
    @Autowired
    private AirwayRepository airways;
    @Autowired
    private FlightPlanRepository plans;
    @Autowired
    private NavigationDataService navigation;

    /** Two aerodromes about 600 nm apart on a westbound track, joined by one airway. */
    private static final GeoPoint EGXX = GeoPoint.of(51.5, -0.5);
    private static final GeoPoint ALFA = GeoPoint.of(51.0, -4.0);
    private static final GeoPoint BRAVO = GeoPoint.of(50.0, -9.0);
    private static final GeoPoint CHARL = GeoPoint.of(49.0, -14.0);
    private static final GeoPoint EGYY = GeoPoint.of(48.5, -16.0);

    @BeforeEach
    void seedTheCycle() {
        plans.deleteAll();
        cycles.deleteAll();

        NavigationEntities.CycleEntity cycle = cycles.save(
                new NavigationEntities.CycleEntity(new AiracCycle(2024, 13), "planning test"));

        airports.save(NavigationEntities.AirportEntity.from(airport("EGXX", EGXX), cycle));
        airports.save(NavigationEntities.AirportEntity.from(airport("EGYY", EGYY), cycle));
        waypoints.saveAll(List.of(
                NavigationEntities.WaypointEntity.from(
                        Waypoint.enroute("ALFA", "EG", ALFA), cycle),
                NavigationEntities.WaypointEntity.from(
                        Waypoint.enroute("BRAVO", "EG", BRAVO), cycle),
                NavigationEntities.WaypointEntity.from(
                        Waypoint.enroute("CHARL", "EG", CHARL), cycle)));
        airways.save(NavigationEntities.AirwayEntity.from(airway(), cycle));

        // The service caches a cycle in memory; make it read the one just written.
        navigation.loadCycle("2413");
    }

    private static Airport airport(String icao, GeoPoint position) {
        Runway runway = new Runway(icao, "RW09", position, 90.0, 10_000, 150, 100, 0, 0.0, "IXXX");
        return new Airport(icao, null, position, 100, 6000, 10_000, "C", icao, List.of(runway));
    }

    private static Airway airway() {
        List<AirwayPoint> points = new ArrayList<>();
        String[] fixes = {"ALFA", "BRAVO", "CHARL"};
        for (int i = 0; i < fixes.length; i++) {
            points.add(new AirwayPoint((i + 1) * 10, fixes[i], "EG", DirectionRestriction.BOTH,
                    AirwayLevel.ALL, 5_000, 46_000, i == fixes.length - 1 ? " EE " : "    "));
        }
        return new Airway("UN100", points);
    }

    private String planRequest() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightNumber", "AD300");
        body.put("registration", "G-TEST");
        body.put("aircraftType", "N738");
        body.put("departure", "EGXX");
        body.put("destination", "EGYY");
        body.put("route", "EGXX ALFA UN100 CHARL EGYY");
        body.put("cruiseFlightLevel", 340);
        body.put("offBlocks", "2024-12-30T09:00:00Z");
        body.put("payloadKg", 12000);
        body.put("extraFuelKg", 0);
        return json.writeValueAsString(body);
    }

    @Test
    void plans_a_flight_and_stores_it() throws Exception {
        MvcResult result = mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").exists())
                .andExpect(jsonPath("$.sector").value("EGXX-EGYY"))
                .andExpect(jsonPath("$.aircraftType").value("N738"))
                .andExpect(jsonPath("$.cruiseFlightLevel").value(340))
                .andExpect(jsonPath("$.route").value(
                        org.hamcrest.Matchers.containsString("UN100")))
                .andExpect(jsonPath("$.airacCycle").value("2413"))
                .andExpect(jsonPath("$.fuel.tripKg").isNumber())
                .andExpect(jsonPath("$.fuel.rampKg").isNumber())
                .andReturn();

        assertThat(plans.count()).isEqualTo(1);
        assertThat(result.getResponse().getContentAsString()).contains("estimatedArrival");
    }

    @Test
    void the_stored_plan_comes_back_by_its_reference() throws Exception {
        String reference = json.readTree(mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planRequest()))
                .andReturn().getResponse().getContentAsString()).get("reference").asText();

        mvc.perform(get("/api/plans/{reference}", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("AD300"))
                .andExpect(jsonPath("$.departure").value("EGXX"))
                .andExpect(jsonPath("$.destination").value("EGYY"))
                .andExpect(jsonPath("$.cruiseFlightLevel").value(340))
                .andExpect(jsonPath("$.tripFuelKg").isNumber());
    }

    @Test
    void the_rendered_OFP_comes_back_as_the_text_that_was_stored() throws Exception {
        String reference = json.readTree(mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planRequest()))
                .andReturn().getResponse().getContentAsString()).get("reference").asText();

        String ofp = mvc.perform(get("/api/plans/{reference}/ofp", reference))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse().getContentAsString();

        assertThat(ofp)
                .contains("OPERATIONAL FLIGHT PLAN")
                .contains("AD300")
                .contains("EGXX-EGYY")
                .contains("NAVIGATION LOG")
                .contains("AIRAC 2413");
    }

    @Test
    void a_plan_that_cannot_be_dispatched_is_stored_with_its_objections() throws Exception {
        // No forecast is loaded, so no alternate qualifies and the plan is refused — but it is
        // a computed plan, not a failed request, and both facts must survive.
        MvcResult result = mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(planRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispatchable").value(false))
                .andExpect(jsonPath("$.objections").isNotEmpty())
                .andReturn();

        String reference = json.readTree(result.getResponse().getContentAsString())
                .get("reference").asText();

        mvc.perform(get("/api/plans/{reference}", reference))
                .andExpect(jsonPath("$.dispatchable").value(false))
                .andExpect(jsonPath("$.objections").isNotEmpty());
    }

    @Test
    void an_unknown_reference_is_a_404() throws Exception {
        mvc.perform(get("/api/plans/{reference}", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void an_aerodrome_that_is_not_in_the_cycle_is_a_400_naming_it() throws Exception {
        String body = planRequest().replace("\"EGYY\"", "\"EGZZ\"");

        mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The request cannot be planned"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("EGZZ")));
    }

    @Test
    void an_invalid_request_body_is_a_400_listing_the_failures() throws Exception {
        String body = planRequest().replace("\"departure\":\"EGXX\"", "\"departure\":\"E\"")
                .replace("\"departure\": \"EGXX\"", "\"departure\": \"E\"");

        mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The request is not valid"))
                .andExpect(jsonPath("$.failures").isArray());
    }

    @Test
    void the_navdata_endpoints_serve_what_was_loaded_from_the_database() throws Exception {
        mvc.perform(get("/api/navdata/cycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").value("2413"))
                .andExpect(jsonPath("$.airports").value(2))
                .andExpect(jsonPath("$.airways").value(1));

        mvc.perform(get("/api/navdata/airports/{icao}", "EGXX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runways.length()").value(1));

        mvc.perform(get("/api/navdata/route")
                        .param("route", "EGXX ALFA UN100 CHARL EGYY").param("from", "EGXX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.airways[0]").value("UN100"));
    }
}
