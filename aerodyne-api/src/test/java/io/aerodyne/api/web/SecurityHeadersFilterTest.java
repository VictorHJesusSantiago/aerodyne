package io.aerodyne.api.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityHeadersFilterTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new SecurityHeadersFilter())
                .build();
    }

    @Test
    void adds_defensive_headers_to_plain_http_responses() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy",
                        SecurityHeadersFilter.PERMISSIONS_POLICY))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void adds_hsts_only_when_the_request_is_secure() throws Exception {
        mvc.perform(get("/probe").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        "max-age=31536000; includeSubDomains"));
    }

    @Controller
    private static final class ProbeController {

        @GetMapping("/probe")
        ResponseEntity<String> probe() {
            return ResponseEntity.ok("ok");
        }
    }
}
