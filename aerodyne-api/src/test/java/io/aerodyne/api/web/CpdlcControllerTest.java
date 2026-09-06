package io.aerodyne.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aerodyne.api.service.CpdlcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CpdlcControllerTest {

    private static final String AIRCRAFT = "GABCD";

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CpdlcController(new CpdlcService()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private String clearanceBody() throws Exception {
        return json.writeValueAsString(Map.of(
                "from", "EGLL_ATC",
                "to", AIRCRAFT,
                "responseRequirement", "WU",
                "elements", List.of(Map.of("code", "UM20", "parameters", List.of("FL350")))));
    }

    @Test
    void sends_a_clearance_and_renders_it_both_ways() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                        .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(0))
                .andExpect(jsonPath("$.referenceId").doesNotExist())
                .andExpect(jsonPath("$.text").value("CLIMB TO AND MAINTAIN FL350"))
                .andExpect(jsonPath("$.wireFormat").value(
                        org.hamcrest.Matchers.startsWith("CPDLC/1/0/-/EGLL_ATC/GABCD/")))
                .andExpect(jsonPath("$.responseRequirement").value("WU"));
    }

    @Test
    void a_clearance_that_has_been_sent_is_outstanding_until_it_is_answered() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()));

        mvc.perform(get("/api/cpdlc/{aircraft}/outstanding", AIRCRAFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.outstanding.length()").value(1))
                .andExpect(jsonPath("$.protocolErrors.length()").value(0));

        mvc.perform(post("/api/cpdlc/{aircraft}/messages/{reference}/reply", AIRCRAFT, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "DM0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceId").value(0))
                .andExpect(jsonPath("$.text").value("WILCO"));

        mvc.perform(get("/api/cpdlc/{aircraft}/outstanding", AIRCRAFT))
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.outstanding.length()").value(0));
    }

    @Test
    void a_STANDBY_leaves_the_clearance_outstanding() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()));

        mvc.perform(post("/api/cpdlc/{aircraft}/messages/{reference}/reply", AIRCRAFT, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "DM2"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/cpdlc/{aircraft}/outstanding", AIRCRAFT))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.acknowledgedWithStandby.length()").value(1));
    }

    @Test
    void accepts_a_message_that_arrived_already_encoded() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/received", AIRCRAFT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("CPDLC/1/7/-/GABCD/EGLL_ATC/2024-06-15T08:00:00Z/WU/DM9:FL390"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(7))
                .andExpect(jsonPath("$.text").value("REQUEST CLIMB TO FL390"));
    }

    @Test
    void a_line_that_is_not_a_message_is_a_400_naming_what_was_wrong() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/received", AIRCRAFT)
                        .contentType(MediaType.TEXT_PLAIN).content("CLIMB TO FL350"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The submitted data could not be read"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("expected 9 fields")));
    }

    @Test
    void answering_a_message_that_is_not_open_is_a_404() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages/{reference}/reply", AIRCRAFT, 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "DM0"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("99")));
    }

    @Test
    void an_answer_the_requirement_does_not_permit_is_recorded_as_a_protocol_error()
            throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()));

        mvc.perform(post("/api/cpdlc/{aircraft}/messages/{reference}/reply", AIRCRAFT, 0)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "DM4"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/cpdlc/{aircraft}/outstanding", AIRCRAFT))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.protocolErrors.length()").value(1))
                .andExpect(jsonPath("$.protocolErrors[0]").value(
                        org.hamcrest.Matchers.containsString("AFFIRM is not a valid answer")));
    }

    @Test
    void a_session_that_was_never_opened_is_a_404() throws Exception {
        mvc.perform(get("/api/cpdlc/{aircraft}/outstanding", "GZZZZ"))
                .andExpect(status().isNotFound());
    }

    @Test
    void keeps_the_transcript_and_lists_the_connected_aircraft() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()));

        mvc.perform(get("/api/cpdlc/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(AIRCRAFT));

        mvc.perform(get("/api/cpdlc/{aircraft}/transcript", AIRCRAFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void ending_a_session_returns_the_exchange_and_closes_it() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                .contentType(MediaType.APPLICATION_JSON).content(clearanceBody()));

        mvc.perform(post("/api/cpdlc/{aircraft}/end", AIRCRAFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/cpdlc/sessions"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void a_request_missing_a_required_field_is_a_400_listing_the_failures() throws Exception {
        mvc.perform(post("/api/cpdlc/{aircraft}/messages", AIRCRAFT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "from", "EGLL_ATC", "to", AIRCRAFT,
                                "responseRequirement", "WU",
                                "elements", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The request is not valid"));
    }
}
