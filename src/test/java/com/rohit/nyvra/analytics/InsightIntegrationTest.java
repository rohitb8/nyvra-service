package com.rohit.nyvra.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.analytics.dto.InsightResponse;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of insights against a real database: the raise / supersede / clear lifecycle that
 * the rules engine drives, then listing (status and severity filters, precedence order, paging) and
 * dismissing through the API, including ownership scoping. Each test creates its own user.
 */
@AutoConfigureMockMvc
class InsightIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates users directly so tests know their ids. */
    @Autowired
    private UserProfileRepository users;

    /** The service whose {@code raise} and {@code clear} entry points the tests call. */
    @Autowired
    private InsightService service;

    /**
     * Authenticates a request as the given user.
     *
     * @param user the user, whose Keycloak subject becomes the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(UserProfile user) {
        return jwt().jwt(jwt -> jwt.subject(user.getKeycloakSubject()));
    }

    /** @return a freshly stored user */
    private UserProfile newUser() {
        return users.save(UserProfileMother.aUserProfile());
    }

    /** Raising the same finding twice keeps one insight; a changed one supersedes the old one. */
    @Test
    void raiseIsIdempotentAndSupersedesOnChange() throws Exception {
        UserProfile user = newUser();

        InsightResponse first = service.raise(user.getId(), "LOW_EMERGENCY_FUND", InsightSeverity.WARN,
            "Your emergency fund covers 4 months.", Map.of("months", 4));
        InsightResponse again = service.raise(user.getId(), "LOW_EMERGENCY_FUND", InsightSeverity.WARN,
            "Your emergency fund covers 4 months.", Map.of("months", 4));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.rulesetVersion()).isEqualTo("2026.1");

        InsightResponse changed = service.raise(user.getId(), "LOW_EMERGENCY_FUND", InsightSeverity.CRITICAL,
            "Your emergency fund covers 2 months.", Map.of("months", 2));
        assertThat(changed.id()).isNotEqualTo(first.id());

        mockMvc.perform(get("/api/v1/insights").with(as(user)))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(changed.id().toString()))
            .andExpect(jsonPath("$.content[0].severity").value("CRITICAL"))
            .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.content[0].evidence.months").value(2));
        mockMvc.perform(get("/api/v1/insights").param("status", "SUPERSEDED").with(as(user)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].id").value(first.id().toString()));
    }

    /** Clearing retires the live insight; clearing again is harmless; the rule can fire anew later. */
    @Test
    void clearSupersedesTheLiveInsight() throws Exception {
        UserProfile user = newUser();
        service.raise(user.getId(), "HIGH_CARD_UTILISATION", InsightSeverity.WARN, "Cards are 60% used.", null);

        service.clear(user.getId(), "HIGH_CARD_UTILISATION");
        service.clear(user.getId(), "HIGH_CARD_UTILISATION");
        mockMvc.perform(get("/api/v1/insights").with(as(user))).andExpect(jsonPath("$.content", hasSize(0)));

        service.raise(user.getId(), "HIGH_CARD_UTILISATION", InsightSeverity.WARN, "Cards are 60% used.", null);
        mockMvc.perform(get("/api/v1/insights").with(as(user))).andExpect(jsonPath("$.content", hasSize(1)));
    }

    /** A dismissed insight stays dismissed while the finding is unchanged, and returns when it changes. */
    @Test
    void dismissedInsightStaysHiddenUntilTheFindingChanges() throws Exception {
        UserProfile user = newUser();
        InsightResponse raised = service.raise(user.getId(), "STALE_ASSET", InsightSeverity.INFO,
            "Revalue your flat.", null);

        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", raised.id()).with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISMISSED"))
            .andExpect(jsonPath("$.dismissedAt").exists());
        // dismissing again is a no-op
        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", raised.id()).with(as(user)))
            .andExpect(status().isOk());

        service.raise(user.getId(), "STALE_ASSET", InsightSeverity.INFO, "Revalue your flat.", null);
        mockMvc.perform(get("/api/v1/insights").with(as(user))).andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/insights").param("status", "DISMISSED").with(as(user)))
            .andExpect(jsonPath("$.content", hasSize(1)));

        service.raise(user.getId(), "STALE_ASSET", InsightSeverity.INFO, "Revalue your flat and car.", null);
        mockMvc.perform(get("/api/v1/insights").with(as(user)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].message").value("Revalue your flat and car."));
    }

    /** A superseded insight can't be dismissed (409) and an unknown or foreign one is 404. */
    @Test
    void dismissErrors() throws Exception {
        UserProfile owner = newUser();
        UserProfile other = newUser();
        InsightResponse old = service.raise(owner.getId(), "R1", InsightSeverity.INFO, "a", null);
        InsightResponse current = service.raise(owner.getId(), "R1", InsightSeverity.WARN, "b", null);

        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", old.id()).with(as(owner)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INSIGHT_SUPERSEDED"));
        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", current.id()).with(as(other)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", UUID.randomUUID()).with(as(owner)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/insights/not-a-uuid/dismiss").with(as(owner)))
            .andExpect(status().isBadRequest());
    }

    /** The list is ordered CRITICAL, WARN, INFO, filters by severity, pages, and is private to the user. */
    @Test
    void listOrdersFiltersPagesAndIsolatesUsers() throws Exception {
        UserProfile user = newUser();
        UserProfile other = newUser();
        service.raise(user.getId(), "A", InsightSeverity.INFO, "info", null);
        service.raise(user.getId(), "B", InsightSeverity.CRITICAL, "critical", null);
        service.raise(user.getId(), "C", InsightSeverity.WARN, "warn", null);
        service.raise(other.getId(), "A", InsightSeverity.CRITICAL, "not yours", null);

        mockMvc.perform(get("/api/v1/insights").with(as(user)))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].severity").value("CRITICAL"))
            .andExpect(jsonPath("$.content[1].severity").value("WARN"))
            .andExpect(jsonPath("$.content[2].severity").value("INFO"));
        mockMvc.perform(get("/api/v1/insights").param("severity", "WARN", "INFO").with(as(user)))
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].severity").value("WARN"));
        mockMvc.perform(get("/api/v1/insights").param("size", "2").param("page", "1").with(as(user)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/insights").param("size", "0").with(as(user)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/insights").param("status", "BOGUS").with(as(user)))
            .andExpect(status().isBadRequest());
    }

    /** Anonymous callers get 401. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/insights")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/insights/{id}/dismiss", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
