package com.rohit.nyvra.dashboard;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of {@code GET /api/v1/dashboard/summary} against a real database: a brand-new user still gets
 * every section, data created through the other modules' endpoints shows up, and the endpoint needs a token.
 */
@AutoConfigureMockMvc
class DashboardControllerIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controllers through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Authenticates a request as the given Keycloak subject.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** A user with no data gets every section, an unevaluated score and no {@code unavailable} list. */
    @Test
    void newUserGetsEverySectionWithEmptyValues() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").with(as("dash-" + UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netWorth.netWorth.amount").value("0.00"))
            .andExpect(jsonPath("$.portfolio.holdingCount").value(0))
            .andExpect(jsonPath("$.healthScore.band").value("INSUFFICIENT_DATA"))
            .andExpect(jsonPath("$.goals.activeCount").value(0))
            .andExpect(jsonPath("$.goals.upcoming", hasSize(0)))
            .andExpect(jsonPath("$.income.windowMonths").value(3))
            .andExpect(jsonPath("$.spending.totalSpend.amount").value("0.00"))
            .andExpect(jsonPath("$", not(hasKey("unavailable"))));
    }

    /** An account balance and a goal created through their endpoints appear in the summary. */
    @Test
    void reflectsDataFromOtherModules() throws Exception {
        String subject = "dash-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"SAVINGS","label":"Acct","currentBalance":{"amount":"5000.00","currency":"INR"}}"""))
            .andExpect(status().isCreated());
        LocalDate target = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusYears(1);
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Trip","type":"TRAVEL","targetAmount":{"amount":"12000.00","currency":"INR"},
                     "targetDate":"%s"}""".formatted(target)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/dashboard/summary").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netWorth.totalAssets.amount").value("5000.00"))
            .andExpect(jsonPath("$.goals.activeCount").value(1))
            .andExpect(jsonPath("$.goals.upcoming[0].name").value("Trip"));
    }

    /** Without a token the endpoint answers 401. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
    }
}
