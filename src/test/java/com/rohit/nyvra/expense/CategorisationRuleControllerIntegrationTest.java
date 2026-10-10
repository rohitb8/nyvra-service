package com.rohit.nyvra.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.partition.MonthlyPartitions;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileMother;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of {@code /api/v1/categorisation-rules}: CRUD, matcher validation, ownership, paging and sorting,
 * and the asynchronous {@code applyToExisting} back-fill of past expenses.
 */
@AutoConfigureMockMvc
class CategorisationRuleControllerIntegrationTest extends AbstractIntegrationTest {

    /** Seeded in V4.1: "Food & dining" (ESSENTIAL), top level. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");

    /** Seeded in V4.1: "Food delivery", a child of {@link #FOOD}. */
    private static final UUID FOOD_DELIVERY = UUID.fromString("ce266dee-f9de-5b83-bfcf-e155227bb0ec");

    /** Seeded in V4.1: "Shopping", top level. */
    private static final UUID SHOPPING = UUID.fromString("777df330-9c34-5d70-856b-cc533d7b77f6");

    /** Issues requests against the full application. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates the users the tests act as. */
    @Autowired
    private UserProfileRepository users;

    /** Seeds and inspects expenses for the back-fill tests. */
    @Autowired
    private ExpenseRepository expenses;

    /** Makes sure the expense partitions exist for the seeded dates. */
    @Autowired
    private MonthlyPartitions partitions;

    /**
     * A signed-in test user.
     *
     * @param subject the JWT subject
     * @param user    the persisted profile
     */
    private record Seed(String subject, UserProfile user) {
    }

    /**
     * Persists a fresh user.
     *
     * @return the user and the subject to sign in with
     */
    private Seed seedUser() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        return new Seed(user.getKeycloakSubject(), user);
    }

    /**
     * Authenticates a request as the user.
     *
     * @param seed the user
     * @return a post-processor adding the JWT
     */
    private static RequestPostProcessor as(Seed seed) {
        return jwt().jwt(jwt -> jwt.subject(seed.subject()));
    }

    /**
     * POSTs a rule.
     *
     * @param seed     the caller
     * @param query    a query string including the leading {@code ?}, or empty
     * @param type     the matcher type
     * @param value    the matcher value
     * @param category the target category
     * @param extra    extra JSON members, each starting with a comma, or empty
     * @return the result, for assertions
     * @throws Exception on request failure
     */
    private ResultActions createRule(Seed seed, String query, String type, String value, UUID category, String extra)
            throws Exception {
        String body = "{\"matcherType\":\"%s\",\"matcherValue\":\"%s\",\"categoryId\":\"%s\",\"necessity\":\"DISCRETIONARY\"%s}"
            .formatted(type, value, category, extra);
        return mockMvc.perform(post("/api/v1/categorisation-rules" + query).with(as(seed))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * Creates a rule and returns its id.
     *
     * @param seed     the caller
     * @param value    the merchant regex
     * @param category the target category
     * @param extra    extra JSON members, each starting with a comma, or empty
     * @return the new rule's id
     * @throws Exception on request failure
     */
    private String createRuleId(Seed seed, String value, UUID category, String extra) throws Exception {
        String body = createRule(seed, "", "MERCHANT_REGEX", value, category, extra).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * PATCHes a rule.
     *
     * @param seed the caller
     * @param id   the rule id
     * @param json the request body
     * @return the result, for assertions
     * @throws Exception on request failure
     */
    private ResultActions patchRule(Seed seed, String id, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/categorisation-rules/" + id).with(as(seed))
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /**
     * Saves an expense with the given origin and merchant in September 2026.
     *
     * @param seed     the owner
     * @param merchant the merchant
     * @param origin   the origin, which decides whether the category counts as chosen by the user
     * @return the saved expense
     */
    private Expense saveExpense(Seed seed, String merchant, ExpenseOrigin origin) {
        partitions.ensureMonth("expense", YearMonth.of(2026, 9));
        return expenses.save(new Expense(seed.user().getId(), LocalDate.of(2026, 9, 5), Money.inr("300.00"), SHOPPING,
            null, merchant, Necessity.DISCRETIONARY, origin, origin == ExpenseOrigin.AA ? UUID.randomUUID() : null));
    }

    /**
     * Creating a rule defaults its priority and echoes the category; the rule can then be read back.
     *
     * @throws Exception on request failure
     */
    @Test
    void createsAndReadsARule() throws Exception {
        Seed seed = seedUser();

        String body = createRule(seed, "", "MERCHANT_REGEX", "(?i)swiggy|zomato", FOOD_DELIVERY, "")
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("http://localhost/api/v1/categorisation-rules/")))
            .andExpect(jsonPath("$.matcherType", equalTo("MERCHANT_REGEX")))
            .andExpect(jsonPath("$.matcherValue", equalTo("(?i)swiggy|zomato")))
            .andExpect(jsonPath("$.category.name", equalTo("Food delivery")))
            .andExpect(jsonPath("$.necessity", equalTo("DISCRETIONARY")))
            .andExpect(jsonPath("$.priority", equalTo(100)))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/v1/categorisation-rules/" + id).with(as(seed))).andExpect(status().isOk())
            .andExpect(jsonPath("$.id", equalTo(id)));
    }

    /**
     * Regexes must compile, MCCs must be four digits, the category must exist, and duplicates are refused.
     *
     * @throws Exception on request failure
     */
    @Test
    void validatesTheRule() throws Exception {
        Seed seed = seedUser();

        createRule(seed, "", "MERCHANT_REGEX", "(unclosed", FOOD, "").andExpect(status().isBadRequest());
        createRule(seed, "", "NARRATION_REGEX", "[", FOOD, "").andExpect(status().isBadRequest());
        createRule(seed, "", "MCC", "58", FOOD, "").andExpect(status().isBadRequest());
        createRule(seed, "", "MCC", "5812", FOOD, "").andExpect(status().isCreated());
        createRule(seed, "", "MERCHANT_REGEX", "x".repeat(201), FOOD, "").andExpect(status().isBadRequest());
        createRule(seed, "", "MERCHANT_REGEX", "shop", UUID.randomUUID(), "").andExpect(status().isNotFound());
        createRule(seed, "", "MERCHANT_REGEX", "shop", FOOD, ",\"priority\":1001").andExpect(status().isBadRequest());
        createRule(seed, "", "MERCHANT_REGEX", "shop", FOOD, ",\"priority\":-1").andExpect(status().isBadRequest());
        createRule(seed, "", "BOGUS", "shop", FOOD, "").andExpect(status().isBadRequest());

        createRule(seed, "", "MERCHANT_REGEX", "dup", FOOD, "").andExpect(status().isCreated());
        createRule(seed, "", "MERCHANT_REGEX", "dup", SHOPPING, "").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("RULE_EXISTS")));
    }

    /**
     * The list is the caller's own rules, highest priority first by default, pageable and sortable.
     *
     * @throws Exception on request failure
     */
    @Test
    void listsOnlyTheCallersRulesInPriorityOrder() throws Exception {
        Seed alice = seedUser();
        Seed bob = seedUser();
        createRuleId(alice, "low", FOOD, ",\"priority\":10");
        createRuleId(alice, "high", FOOD, ",\"priority\":900");
        createRuleId(alice, "mid", FOOD, ",\"priority\":500");
        createRuleId(bob, "bobs", FOOD, "");

        mockMvc.perform(get("/api/v1/categorisation-rules").with(as(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements", equalTo(3)))
            .andExpect(jsonPath("$.content[0].matcherValue", equalTo("high")))
            .andExpect(jsonPath("$.content[1].matcherValue", equalTo("mid")))
            .andExpect(jsonPath("$.content[2].matcherValue", equalTo("low")));
        mockMvc.perform(get("/api/v1/categorisation-rules").param("sort", "priority,asc").param("size", "2")
                .with(as(alice)))
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].matcherValue", equalTo("low")))
            .andExpect(jsonPath("$.totalPages", equalTo(2)));
        mockMvc.perform(get("/api/v1/categorisation-rules").param("sort", "category,asc").with(as(alice)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/categorisation-rules").param("size", "101").with(as(alice)))
            .andExpect(status().isBadRequest());
    }

    /**
     * Patching changes only the given fields and re-validates the resulting matcher.
     *
     * @throws Exception on request failure
     */
    @Test
    void patchesARule() throws Exception {
        Seed seed = seedUser();
        String id = createRuleId(seed, "swiggy", FOOD, "");
        createRuleId(seed, "zomato", FOOD, "");

        patchRule(seed, id, "{\"priority\":7,\"categoryId\":\"" + SHOPPING + "\",\"necessity\":\"ESSENTIAL\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.matcherValue", equalTo("swiggy")))
            .andExpect(jsonPath("$.priority", equalTo(7)))
            .andExpect(jsonPath("$.category.name", equalTo("Shopping")))
            .andExpect(jsonPath("$.necessity", equalTo("ESSENTIAL")));
        patchRule(seed, id, "{\"matcherValue\":\"(oops\"}").andExpect(status().isBadRequest());
        patchRule(seed, id, "{\"matcherType\":\"MCC\"}").andExpect(status().isBadRequest());
        patchRule(seed, id, "{\"matcherValue\":\"zomato\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("RULE_EXISTS")));
        patchRule(seed, id, "{\"categoryId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        patchRule(seed, id, "{\"priority\":5000}").andExpect(status().isBadRequest());
    }

    /**
     * A rule can be deleted; someone else's rule, and a deleted one, look missing.
     *
     * @throws Exception on request failure
     */
    @Test
    void deletesARuleAndHidesItFromOthers() throws Exception {
        Seed alice = seedUser();
        Seed bob = seedUser();
        String id = createRuleId(alice, "swiggy", FOOD, "");

        mockMvc.perform(get("/api/v1/categorisation-rules/" + id).with(as(bob))).andExpect(status().isNotFound());
        patchRule(bob, id, "{\"priority\":1}").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/categorisation-rules/" + id).with(as(bob))).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/categorisation-rules/" + id).with(as(alice))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/categorisation-rules/" + id).with(as(alice))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/categorisation-rules/" + id).with(as(alice))).andExpect(status().isNotFound());
    }

    /**
     * With {@code applyToExisting=true} matching auto-categorised expenses move to the rule's category, while ones
     * the user categorised by hand, non-matching ones and other users' expenses are left alone.
     *
     * @throws Exception on request failure
     */
    @Test
    void applyToExistingRecategorisesOnlyAutoCategorisedMatches() throws Exception {
        Seed seed = seedUser();
        Seed other = seedUser();
        Expense auto = saveExpense(seed, "SWIGGY ORDER", ExpenseOrigin.AA);
        Expense byHand = saveExpense(seed, "SWIGGY INSTAMART", ExpenseOrigin.MANUAL);
        Expense unrelated = saveExpense(seed, "BIG BAZAAR", ExpenseOrigin.AA);
        Expense othersExpense = saveExpense(other, "SWIGGY ORDER", ExpenseOrigin.AA);
        assertThat(auto.getCategorySource()).isEqualTo(CategorySource.AUTO);
        assertThat(byHand.getCategorySource()).isEqualTo(CategorySource.USER);

        createRule(seed, "?applyToExisting=true", "MERCHANT_REGEX", "(?i)swiggy", FOOD_DELIVERY,
            ",\"necessity\":\"DISCRETIONARY\"").andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Expense moved = expenses.findById(auto.getId()).orElseThrow();
            assertThat(moved.getCategoryId()).isEqualTo(FOOD);
            assertThat(moved.getSubcategoryId()).isEqualTo(FOOD_DELIVERY);
            assertThat(moved.getCategorySource()).isEqualTo(CategorySource.AUTO);
        });
        assertThat(expenses.findById(byHand.getId()).orElseThrow().getCategoryId()).isEqualTo(SHOPPING);
        assertThat(expenses.findById(unrelated.getId()).orElseThrow().getCategoryId()).isEqualTo(SHOPPING);
        assertThat(expenses.findById(othersExpense.getId()).orElseThrow().getCategoryId()).isEqualTo(SHOPPING);
    }

    /**
     * Without {@code applyToExisting} history is untouched, and a hand recategorisation sticks as user-chosen.
     *
     * @throws Exception on request failure
     */
    @Test
    void historyIsUntouchedByDefaultAndAHandEditIsMarkedAsUserChosen() throws Exception {
        Seed seed = seedUser();
        Expense auto = saveExpense(seed, "SWIGGY ORDER", ExpenseOrigin.AA);

        createRuleId(seed, "(?i)swiggy", FOOD_DELIVERY, "");
        Thread.sleep(500);
        assertThat(expenses.findById(auto.getId()).orElseThrow().getCategoryId()).isEqualTo(SHOPPING);

        mockMvc.perform(patch("/api/v1/expenses/" + auto.getId()).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":\"" + FOOD + "\"}")).andExpect(status().isOk());
        assertThat(expenses.findById(auto.getId()).orElseThrow().getCategorySource()).isEqualTo(CategorySource.USER);
    }
}
