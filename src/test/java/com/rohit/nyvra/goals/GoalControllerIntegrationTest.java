package com.rohit.nyvra.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the goals API against a real PostgreSQL (Testcontainers) with a stub JWT decoder: ownership
 * scoping (404 for other users), validation (400), business-rule errors (422/409 with codes such as
 * {@code GOAL_DATES_INVALID}), progress after contributions, and the {@link GoalProgressSource} other modules
 * read. Each test uses a fresh subject so tests never see each other's data.
 */
@AutoConfigureMockMvc
class GoalControllerIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** The cross-module progress view under test. */
    @Autowired
    private GoalProgressSource progressSource;

    /** Resolves the internal user id that JIT provisioning created for a subject. */
    @Autowired
    private UserProfileRepository users;

    /**
     * Authenticates a request as the given Keycloak subject, with a derived email claim.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "goal-" + UUID.randomUUID();
    }

    /** @return today's date in the user's display time zone */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * Creates a 120000.00 INR goal starting today through the API and checks the 201 response.
     *
     * @param subject the caller
     * @param daysAhead days from today until the target date
     * @return the new goal id
     * @throws Exception on request failure
     */
    private String createGoal(String subject, int daysAhead) throws Exception {
        String body = mockMvc.perform(post("/api/v1/goals").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Home","type":"HOME","targetAmount":{"amount":"120000.00","currency":"INR"},
                     "targetDate":"%s","weight":2}""".formatted(today().plusDays(daysAhead))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.weight").value(2))
            .andExpect(jsonPath("$.startDate").value(today().toString()))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Adds a contribution through the API, expecting success.
     *
     * @param subject the caller
     * @param goalId the goal
     * @param amount the amount as a decimal string
     * @throws Exception on request failure
     */
    private void contribute(String subject, String goalId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/goals/" + goalId + "/contributions").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":\"%s\",\"currency\":\"INR\"}}".formatted(amount)))
            .andExpect(status().isCreated());
    }

    /** A new goal has zero allocation, nothing required on day zero, and the full target remaining. */
    @Test
    void createReturnsGoalWithInitialProgress() throws Exception {
        String subject = newSubject();
        String id = createGoal(subject, 100);
        mockMvc.perform(get("/api/v1/goals/" + id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.progress.allocated.amount").value("0.00"))
            .andExpect(jsonPath("$.progress.remaining.amount").value("120000.00"))
            .andExpect(jsonPath("$.progress.progressRatio").value(1.0))
            .andExpect(jsonPath("$.progress.daysRemaining").value(100))
            .andExpect(jsonPath("$.progress.achieved").value(false));
    }

    /** Contributions add up in the goal's progress and can be listed and deleted. */
    @Test
    void contributionsAccumulateAndCanBeDeleted() throws Exception {
        String subject = newSubject();
        String id = createGoal(subject, 100);
        contribute(subject, id, "30000.00");
        contribute(subject, id, "15000.50");

        mockMvc.perform(get("/api/v1/goals/" + id).with(as(subject)))
            .andExpect(jsonPath("$.progress.allocated.amount").value("45000.50"))
            .andExpect(jsonPath("$.progress.percentComplete").value(37.5));

        String list = mockMvc.perform(get("/api/v1/goals/" + id + "/contributions").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andReturn().getResponse().getContentAsString();
        String contributionId = JsonPath.read(list, "$.content[0].id");

        mockMvc.perform(delete("/api/v1/goals/" + id + "/contributions/" + contributionId).with(as(subject)))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/goals/" + id + "/contributions").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)));
    }

    /** Another user's goal and contributions look like they do not exist. */
    @Test
    void otherUsersGoalIsNotFound() throws Exception {
        String owner = newSubject();
        String id = createGoal(owner, 100);
        String intruder = newSubject();
        mockMvc.perform(get("/api/v1/goals/" + id).with(as(intruder))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/goals/" + id).with(as(intruder)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/goals/" + id).with(as(intruder))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/goals/" + id + "/contributions").with(as(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/goals").with(as(intruder)))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Invalid input is rejected with the documented codes. */
    @Test
    void validationAndBusinessRules() throws Exception {
        String subject = newSubject();
        String blank = """
            {"name":" ","type":"HOME","targetAmount":{"amount":"100.00","currency":"INR"},"targetDate":"%s"}"""
            .formatted(today().plusDays(5));
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(blank)).andExpect(status().isBadRequest());

        String zero = """
            {"name":"A","type":"HOME","targetAmount":{"amount":"0.00","currency":"INR"},"targetDate":"%s"}"""
            .formatted(today().plusDays(5));
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(zero)).andExpect(status().isBadRequest());

        String usd = """
            {"name":"A","type":"HOME","targetAmount":{"amount":"10.00","currency":"USD"},"targetDate":"%s"}"""
            .formatted(today().plusDays(5));
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(usd)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));

        String past = """
            {"name":"A","type":"HOME","targetAmount":{"amount":"10.00","currency":"INR"},"targetDate":"%s"}"""
            .formatted(today());
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(past)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("GOAL_TARGET_DATE_IN_PAST"));

        String backwards = """
            {"name":"A","type":"HOME","targetAmount":{"amount":"10.00","currency":"INR"},
             "startDate":"%s","targetDate":"%s"}""".formatted(today().plusDays(20), today().plusDays(10));
        mockMvc.perform(post("/api/v1/goals").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(backwards)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("GOAL_DATES_INVALID"));

        String id = createGoal(subject, 30);
        mockMvc.perform(patch("/api/v1/goals/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetDate\":\"%s\"}".formatted(today())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("GOAL_DATES_INVALID"));

        mockMvc.perform(post("/api/v1/goals/" + id + "/contributions").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":\"5.00\",\"currency\":\"INR\"},\"contributedOn\":\"%s\"}"
                    .formatted(today().plusDays(2))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CONTRIBUTION_DATE_IN_FUTURE"));

        mockMvc.perform(get("/api/v1/goals?sort=bogus,asc").with(as(subject))).andExpect(status().isBadRequest());
    }

    /** A partial update changes only the sent fields, and archived goals refuse contributions. */
    @Test
    void updateAndArchive() throws Exception {
        String subject = newSubject();
        String id = createGoal(subject, 100);
        mockMvc.perform(patch("/api/v1/goals/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Dream home \",\"targetAmount\":{\"amount\":\"200000.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Dream home"))
            .andExpect(jsonPath("$.targetAmount.amount").value("200000.00"))
            .andExpect(jsonPath("$.weight").value(2));

        mockMvc.perform(patch("/api/v1/goals/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ARCHIVED\"}")).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(post("/api/v1/goals/" + id + "/contributions").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("GOAL_ARCHIVED"));

        mockMvc.perform(get("/api/v1/goals?status=ACTIVE").with(as(subject)))
            .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/goals?status=ARCHIVED").with(as(subject)))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** Deleting a goal removes it and its contributions. */
    @Test
    void deleteRemovesGoal() throws Exception {
        String subject = newSubject();
        String id = createGoal(subject, 100);
        contribute(subject, id, "10.00");
        mockMvc.perform(delete("/api/v1/goals/" + id).with(as(subject))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/goals/" + id).with(as(subject))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/goals").with(as(subject))).andExpect(jsonPath("$.content", hasSize(0)));
    }

    /** The cross-module view covers active goals only and carries weight and the clamped ratio. */
    @Test
    void progressSourceReportsActiveGoalsOnly() throws Exception {
        String subject = newSubject();
        String active = createGoal(subject, 100);
        String archived = createGoal(subject, 100);
        mockMvc.perform(patch("/api/v1/goals/" + archived).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        contribute(subject, active, "6000.00");

        mockMvc.perform(get("/api/v1/goals/" + active).with(as(subject))).andExpect(status().isOk());
        UserProfile user = users.findByKeycloakSubject(subject).orElseThrow();

        List<GoalProgressSnapshot> day0 = progressSource.activeGoalProgress(user.getId(), today());
        assertThat(day0).hasSize(1);
        assertThat(day0.get(0).goalId()).isEqualTo(UUID.fromString(active));
        assertThat(day0.get(0).weight()).isEqualTo(2);
        assertThat(day0.get(0).progressRatio()).isEqualByComparingTo("1");

        // after 10 of 100 days 12000.00 is required, so 6000.00 saved is half way there
        List<GoalProgressSnapshot> day10 = progressSource.activeGoalProgress(user.getId(), today().plusDays(10));
        assertThat(day10.get(0).progressRatio()).isEqualByComparingTo("0.5");

        assertThat(progressSource.activeGoalProgress(UUID.randomUUID(), today())).isEmpty();
    }

    /** Unauthenticated calls are rejected. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/goals")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", equalTo("UNAUTHENTICATED")));
    }
}
