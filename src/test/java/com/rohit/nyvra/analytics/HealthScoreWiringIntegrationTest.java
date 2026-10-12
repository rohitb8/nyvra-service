package com.rohit.nyvra.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the health-score wiring: data entered through the income, expense and account APIs is
 * gathered by {@link DriverMetricsAssembler}, scored and stored by {@link HealthScoreRecomputer}, and served by
 * {@code GET /health-score}; the debounce of {@code markDirty}/{@code flushDue} is checked too. Each test uses its
 * own user.
 */
@AutoConfigureMockMvc
class HealthScoreWiringIntegrationTest extends AbstractIntegrationTest {

    /** The seeded system "Food" category. */
    private static final String FOOD = "40a8ed3d-45f2-50fe-8806-e94e6a8eb931";

    /** Drives the controllers through the full filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates users directly so tests know their ids. */
    @Autowired
    private UserProfileRepository users;

    /** The recompute entry points under test. */
    @Autowired
    private HealthScoreRecomputer recomputer;

    /** Raw SQL, used to count stored scores. */
    @Autowired
    private JdbcTemplate jdbc;

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

    /**
     * Posts a JSON body and returns the created resource's id.
     *
     * @param user the caller
     * @param path the collection path
     * @param body the JSON body
     * @return the new id
     * @throws Exception on request failure
     */
    private String create(UserProfile user, String path, String body) throws Exception {
        String response = mockMvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * Gives the user a savings account, a salary source with three prior months of income and three prior months
     * of essential and discretionary spending: income 100000, essential 40000, discretionary 20000 a month.
     *
     * @param user the user to seed
     * @throws Exception on request failure
     */
    private void seedThreeMonths(UserProfile user) throws Exception {
        create(user, "/api/v1/accounts", """
            {"type":"SAVINGS","label":"Main","currentBalance":{"amount":"240000.00","currency":"INR"}}""");
        String source = create(user, "/api/v1/income/sources", """
            {"name":"Salary","type":"SALARY","cadence":"MONTHLY",
             "expectedAmount":{"amount":"100000.00","currency":"INR"}}""");
        YearMonth thisMonth = YearMonth.now(ZoneId.of("Asia/Kolkata"));
        for (int back = 1; back <= 3; back++) {
            YearMonth month = thisMonth.minusMonths(back);
            LocalDate first = month.atDay(1);
            create(user, "/api/v1/income/entries", """
                {"sourceId":"%s","periodStart":"%s","periodEnd":"%s",
                 "grossAmount":{"amount":"120000.00","currency":"INR"},
                 "netAmount":{"amount":"100000.00","currency":"INR"},"receivedOn":"%s"}"""
                .formatted(source, first, month.atEndOfMonth(), first));
            create(user, "/api/v1/expenses", expense(first.plusDays(2), "40000.00", "ESSENTIAL"));
            create(user, "/api/v1/expenses", expense(first.plusDays(4), "20000.00", "DISCRETIONARY"));
        }
    }

    /**
     * Builds an expense request body.
     *
     * @param date      the expense date
     * @param amount    the INR amount
     * @param necessity the necessity name
     * @return the JSON body
     */
    private static String expense(LocalDate date, String amount, String necessity) {
        return """
            {"date":"%s","amount":{"amount":"%s","currency":"INR"},"categoryId":"%s","necessity":"%s"}"""
            .formatted(date, amount, FOOD, necessity);
    }

    /**
     * Counts a user's stored scores.
     *
     * @param user the user
     * @return the row count
     */
    private int count(UserProfile user) {
        return jdbc.queryForObject("SELECT count(*) FROM health_score WHERE user_id = ?", Integer.class,
            user.getId());
    }

    /** Real income, spending and account data produce a stored numeric score the endpoint serves. */
    @Test
    void realDataProducesAScore() throws Exception {
        UserProfile user = newUser();
        seedThreeMonths(user);

        HealthScoreResult result = recomputer.recompute(user.getId());

        assertThat(result.band()).isNotEqualTo(ScoreBand.INSUFFICIENT_DATA);
        assertThat(count(user)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/health-score").with(as(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subScores.savings.evaluated").value(true))
            .andExpect(jsonPath("$.subScores.spendingControl.evaluated").value(true))
            .andExpect(jsonPath("$.subScores.emergencyFund.evaluated").value(true))
            .andExpect(jsonPath("$.subScores.debt.evaluated").value(true))
            .andExpect(jsonPath("$.subScores.diversification.evaluated").value(false))
            .andExpect(jsonPath("$.subScores.goalProgress.evaluated").value(false))
            .andExpect(jsonPath("$.subScores.savings.value").value(100.0));
    }

    /** Recomputing with unchanged data writes nothing the second time. */
    @Test
    void recomputingUnchangedDataStoresOnce() throws Exception {
        UserProfile user = newUser();
        seedThreeMonths(user);

        recomputer.recompute(user.getId());
        recomputer.recompute(user.getId());

        assertThat(count(user)).isEqualTo(1);
    }

    /** A user with no data stays INSUFFICIENT_DATA and nothing is stored. */
    @Test
    void userWithoutDataStoresNothing() {
        UserProfile user = newUser();

        HealthScoreResult result = recomputer.recompute(user.getId());

        assertThat(result.band()).isEqualTo(ScoreBand.INSUFFICIENT_DATA);
        assertThat(count(user)).isZero();
    }

    /** A change is only recomputed once the user has been quiet for the debounce period. */
    @Test
    void flushWaitsForTheDebouncePeriod() throws Exception {
        UserProfile user = newUser();
        seedThreeMonths(user);
        // Creating the data above already marked the user dirty through the change listener.
        recomputer.markDirty(user.getId());

        assertThat(recomputer.flushDue(Instant.now())).isZero();
        assertThat(count(user)).isZero();

        assertThat(recomputer.flushDue(Instant.now().plusSeconds(61))).isPositive();
        assertThat(count(user)).isEqualTo(1);
        assertThat(recomputer.flushDue(Instant.now().plusSeconds(120))).isZero();
    }

    /** The daily pass scores every user, including ones with data and ones without. */
    @Test
    void dailyPassScoresUsersWithData() throws Exception {
        UserProfile user = newUser();
        seedThreeMonths(user);

        UUID id = user.getId();
        assertThat(recomputer.recomputeAll()).isPositive();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_score WHERE user_id = ?", Integer.class, id))
            .isEqualTo(1);
    }
}
