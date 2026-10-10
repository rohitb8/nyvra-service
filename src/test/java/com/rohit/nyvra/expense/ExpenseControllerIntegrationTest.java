package com.rohit.nyvra.expense;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
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

@AutoConfigureMockMvc
class ExpenseControllerIntegrationTest extends AbstractIntegrationTest {

    /** Seeded in V4.1: "Food & dining" (ESSENTIAL) and its children. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");
    private static final UUID GROCERIES = UUID.fromString("566d0670-1558-5400-b5d1-5889e0dd8af9");
    private static final UUID DINING_OUT = UUID.fromString("afaf6ba2-b9c8-5100-acdc-1336f5cc1fdd");
    private static final UUID SHOPPING = UUID.fromString("777df330-9c34-5d70-856b-cc533d7b77f6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository users;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private MonthlyPartitions partitions;

    private record Seed(String subject, UserProfile user) {
    }

    private Seed seedUser() {
        UserProfile user = users.save(UserProfileMother.aUserProfile());
        return new Seed(user.getKeycloakSubject(), user);
    }

    private static RequestPostProcessor as(Seed seed) {
        return jwt().jwt(jwt -> jwt.subject(seed.subject()));
    }

    private ResultActions create(Seed seed, String date, String amount, UUID category, String extra) throws Exception {
        String body = """
            {"date":"%s","amount":{"amount":"%s","currency":"INR"},"categoryId":"%s"%s}"""
            .formatted(date, amount, category, extra);
        return mockMvc.perform(post("/api/v1/expenses").with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private String createId(Seed seed, String date, String amount, UUID category, String extra) throws Exception {
        String body = create(seed, date, amount, category, extra).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private java.util.List<Expense> splitsOf(Seed seed) {
        return expenses.findAll().stream()
            .filter(e -> e.getUserId().equals(seed.user().getId()) && e.getParentExpenseId() != null).toList();
    }

    private ResultActions patchJson(Seed seed, String id, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/expenses/" + id).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content(json));
    }

    private ResultActions split(Seed seed, String id, String parts) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/" + id + "/split").with(as(seed))
            .contentType(MediaType.APPLICATION_JSON).content("{\"parts\":" + parts + "}"));
    }

    private static String part(String amount, UUID category) {
        return "{\"amount\":{\"amount\":\"%s\",\"currency\":\"INR\"},\"categoryId\":\"%s\"}".formatted(amount, category);
    }

    @Test
    void createsAManualExpenseWithTheCategoryDefaultNecessity() throws Exception {
        Seed seed = seedUser();

        create(seed, "2026-09-15", "250.50", FOOD, ",\"subcategoryId\":\"" + DINING_OUT + "\",\"merchant\":\" Cafe \"")
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("http://localhost/api/v1/expenses/")))
            .andExpect(jsonPath("$.amount.amount", equalTo("250.50")))
            .andExpect(jsonPath("$.category.name", equalTo("Food & dining")))
            .andExpect(jsonPath("$.subcategory.name", equalTo("Dining out")))
            .andExpect(jsonPath("$.merchant", equalTo("Cafe")))
            .andExpect(jsonPath("$.necessity", equalTo("DISCRETIONARY"))) // the subcategory's default wins
            .andExpect(jsonPath("$.origin", equalTo("MANUAL")))
            .andExpect(jsonPath("$.excludedFromHabits", equalTo(false)))
            .andExpect(jsonPath("$.transactionId").doesNotExist())
            .andExpect(jsonPath("$.splits").doesNotExist());

        create(seed, "2026-09-16", "10.00", FOOD, "").andExpect(jsonPath("$.necessity", equalTo("ESSENTIAL")));
        create(seed, "2026-09-16", "10.00", FOOD, ",\"necessity\":\"DISCRETIONARY\"")
            .andExpect(jsonPath("$.necessity", equalTo("DISCRETIONARY")));
    }

    @Test
    void createsInAMonthThatHasNoPartitionYet() throws Exception {
        Seed seed = seedUser();
        create(seed, "2031-03-10", "99.00", FOOD, "").andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/expenses").param("month", "2031-03").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void rejectsInvalidCreates() throws Exception {
        Seed seed = seedUser();
        create(seed, "2026-09-15", "0.00", FOOD, "").andExpect(status().isBadRequest());
        create(seed, "2026-09-15", "-5.00", FOOD, "").andExpect(status().isBadRequest());
        create(seed, "2026-09-15", "1.234", FOOD, "").andExpect(status().isBadRequest());
        create(seed, "2026-09-15", "5.00", FOOD, ",\"merchant\":\"" + "x".repeat(121) + "\"")
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/expenses").with(as(seed)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":{\"amount\":\"5.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/expenses").with(as(seed)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-09-15\",\"amount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"categoryId\":\""
                    + FOOD + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("UNSUPPORTED_CURRENCY")));
        create(seed, "2026-09-15", "5.00", UUID.randomUUID(), "")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INVALID_CATEGORY")));
        // a subcategory of a different parent
        create(seed, "2026-09-15", "5.00", SHOPPING, ",\"subcategoryId\":\"" + GROCERIES + "\"")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INVALID_SUBCATEGORY")));
    }

    @Test
    void cannotUseAnotherUsersCustomCategory() throws Exception {
        Seed mine = seedUser();
        Seed theirs = seedUser();
        Category custom = categoryFor(theirs, "Their private thing");

        create(mine, "2026-09-15", "5.00", custom.getId(), "").andExpect(status().isUnprocessableEntity());
        create(theirs, "2026-09-15", "5.00", FOOD, ",\"subcategoryId\":\"" + custom.getId() + "\"")
            .andExpect(status().isCreated()); // the owner can use their own custom child
        create(theirs, "2026-09-15", "5.00", SHOPPING, ",\"subcategoryId\":\"" + custom.getId() + "\"")
            .andExpect(status().isUnprocessableEntity()); // but only under its own parent
        create(mine, "2026-09-15", "5.00", FOOD, ",\"subcategoryId\":\"" + custom.getId() + "\"")
            .andExpect(status().isUnprocessableEntity());
    }

    @Autowired
    private CategoryRepository categories;

    private Category categoryFor(Seed seed, String name) {
        return categories.save(Category.custom(seed.user().getId(), FOOD, name, Necessity.DISCRETIONARY));
    }

    @Test
    void listsNewestFirstWithCursorAndNoSplitChildrenAtTopLevel() throws Exception {
        Seed seed = seedUser();
        String parent = null;
        for (int i = 0; i < 12; i++) {
            String id = createId(seed, "2026-09-" + String.format("%02d", 1 + i / 4), "10.00", FOOD, "");
            if (i == 0) {
                parent = id;
            }
        }
        split(seed, parent, "[" + part("4.00", GROCERIES) + "," + part("6.00", DINING_OUT) + "]").andExpect(status().isOk());

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/v1/expenses").param("month", "2026-09").param("limit", "5").with(as(seed));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.<java.util.List<String>>read(body, "$.content[*].id"));
            cursor = body.contains("\"nextCursor\"") ? JsonPath.read(body, "$.nextCursor") : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(12); // children excluded
        assertThat(seen).contains(parent);
    }

    @Test
    void filtersByCategoryNecessityOriginAndMerchant() throws Exception {
        Seed seed = seedUser();
        createId(seed, "2026-09-01", "10.00", FOOD, ",\"subcategoryId\":\"" + GROCERIES + "\",\"merchant\":\"BigBasket\"");
        createId(seed, "2026-09-02", "20.00", FOOD, ",\"necessity\":\"DISCRETIONARY\",\"merchant\":\"Big_Bazaar 100%\"");
        createId(seed, "2026-10-03", "30.00", SHOPPING, "");

        mockMvc.perform(get("/api/v1/expenses").param("categoryId", FOOD.toString()).with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(get("/api/v1/expenses").param("categoryId", GROCERIES.toString()).with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/expenses").param("necessity", "DISCRETIONARY", "DEBT_REPAYMENT").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(get("/api/v1/expenses").param("origin", "AA").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/expenses").param("q", "bIg").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(2)));
        // LIKE wildcards in the search term are literal
        mockMvc.perform(get("/api/v1/expenses").param("q", "_").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/expenses").param("q", "%").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/expenses").param("month", "2026-09").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(get("/api/v1/expenses").param("from", "2026-09-02").param("to", "2026-10-03").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void rejectsBadListParameters() throws Exception {
        Seed seed = seedUser();
        mockMvc.perform(get("/api/v1/expenses").param("month", "2026-09").param("from", "2026-09-01").with(as(seed)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses").param("month", "2026-13").with(as(seed)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses").param("from", "2026-10-01").param("to", "2026-09-01").with(as(seed)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses").param("limit", "201").with(as(seed))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses").param("cursor", "garbage").with(as(seed))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses").param("necessity", "NOPE").with(as(seed))).andExpect(status().isBadRequest());
    }

    @Test
    void cursorIsBoundToItsFilters() throws Exception {
        Seed seed = seedUser();
        createId(seed, "2026-09-01", "1.00", FOOD, "");
        createId(seed, "2026-09-02", "2.00", FOOD, "");
        String body = mockMvc.perform(get("/api/v1/expenses").param("limit", "1").with(as(seed)))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");

        mockMvc.perform(get("/api/v1/expenses").param("cursor", cursor).param("origin", "MANUAL").with(as(seed)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getsOneExpenseAndHidesOthers() throws Exception {
        Seed mine = seedUser();
        Seed theirs = seedUser();
        String own = createId(mine, "2026-09-01", "5.00", FOOD, "");
        String other = createId(theirs, "2026-09-01", "5.00", FOOD, "");

        mockMvc.perform(get("/api/v1/expenses/" + own).with(as(mine)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id", equalTo(own)));
        mockMvc.perform(get("/api/v1/expenses/" + other).with(as(mine))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/expenses/" + other).with(as(mine)).contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/expenses/" + other).with(as(mine))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/expenses/not-a-uuid").with(as(mine))).andExpect(status().isBadRequest());
    }

    @Test
    void updatesManualExpenseIncludingMovingItToAnotherMonth() throws Exception {
        Seed seed = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, ",\"subcategoryId\":\"" + GROCERIES + "\"");

        patchJson(seed, id, "{\"merchant\":\"Dmart\",\"necessity\":\"DISCRETIONARY\",\"excludedFromHabits\":true,"
            + "\"amount\":{\"amount\":\"120.00\",\"currency\":\"INR\"},\"date\":\"2026-11-02\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.merchant", equalTo("Dmart")))
            .andExpect(jsonPath("$.necessity", equalTo("DISCRETIONARY")))
            .andExpect(jsonPath("$.excludedFromHabits", equalTo(true)))
            .andExpect(jsonPath("$.amount.amount", equalTo("120.00")))
            .andExpect(jsonPath("$.date", equalTo("2026-11-02")))
            .andExpect(jsonPath("$.subcategory.name", equalTo("Groceries")));
        mockMvc.perform(get("/api/v1/expenses").param("month", "2026-11").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/expenses").param("month", "2026-09").with(as(seed)))
            .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void changingCategoryClearsTheSubcategoryUnlessGivenAndNullClearsIt() throws Exception {
        Seed seed = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, ",\"subcategoryId\":\"" + GROCERIES + "\"");

        patchJson(seed, id, "{\"merchant\":\"x\"}").andExpect(jsonPath("$.subcategory.name", equalTo("Groceries")));
        patchJson(seed, id, "{\"subcategoryId\":null}").andExpect(jsonPath("$.subcategory").doesNotExist());
        patchJson(seed, id, "{\"subcategoryId\":\"" + GROCERIES + "\"}")
            .andExpect(jsonPath("$.subcategory.name", equalTo("Groceries")));
        patchJson(seed, id, "{\"categoryId\":\"" + SHOPPING + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category.name", equalTo("Shopping")))
            .andExpect(jsonPath("$.subcategory").doesNotExist());
        patchJson(seed, id, "{\"categoryId\":\"" + SHOPPING + "\",\"subcategoryId\":\"" + GROCERIES + "\"}")
            .andExpect(status().isUnprocessableEntity());
        patchJson(seed, id, "{\"merchant\":\"" + "x".repeat(121) + "\"}").andExpect(status().isBadRequest());
        patchJson(seed, id, "{\"amount\":{\"amount\":\"0.00\",\"currency\":\"INR\"}}").andExpect(status().isBadRequest());
    }

    @Test
    void aaDerivedExpensesAreRecategorisableButTheirAmountAndDateAreReadOnly() throws Exception {
        Seed seed = seedUser();
        partitions.ensureMonth("expense", java.time.YearMonth.of(2026, 9));
        Expense aa = expenses.save(new Expense(seed.user().getId(), LocalDate.of(2026, 9, 5), Money.inr("300.00"), FOOD,
            null, "SWIGGY", Necessity.ESSENTIAL, ExpenseOrigin.AA, UUID.randomUUID()));
        String id = aa.getId().toString();

        patchJson(seed, id, "{\"necessity\":\"DISCRETIONARY\",\"categoryId\":\"" + FOOD + "\",\"excludedFromHabits\":true}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.necessity", equalTo("DISCRETIONARY")))
            .andExpect(jsonPath("$.transactionId", equalTo(aa.getTransactionId().toString())));
        patchJson(seed, id, "{\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        patchJson(seed, id, "{\"date\":\"2026-09-06\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        mockMvc.perform(delete("/api/v1/expenses/" + id).with(as(seed)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        assertThat(expenses.findById(aa.getId())).isPresent();
    }

    @Test
    void deletesAManualExpenseAndItsSplits() throws Exception {
        Seed seed = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, "");
        split(seed, id, "[" + part("40.00", GROCERIES) + "," + part("60.00", DINING_OUT) + "]").andExpect(status().isOk());
        assertThat(splitsOf(seed)).hasSize(2);

        mockMvc.perform(delete("/api/v1/expenses/" + id).with(as(seed))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/expenses/" + id).with(as(seed))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/expenses").with(as(seed))).andExpect(jsonPath("$.content", hasSize(0)));
        assertThat(expenses.findAll()).noneMatch(e -> e.getUserId().equals(seed.user().getId()));
    }

    @Test
    void splitsAnExpenseAndUndoesIt() throws Exception {
        Seed seed = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, ",\"excludedFromHabits\":true");

        split(seed, id, "[" + part("40.00", GROCERIES).replace("}", ",\"note\":\"veg\"}")
            + "," + part("60.00", DINING_OUT) + "]")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", equalTo(id)))
            .andExpect(jsonPath("$.amount.amount", equalTo("100.00")))
            .andExpect(jsonPath("$.splits", hasSize(2)))
            .andExpect(jsonPath("$.splits[?(@.note=='veg')].amount.amount").value(org.hamcrest.Matchers.contains("40.00")))
            .andExpect(jsonPath("$.splits[?(@.note=='veg')].necessity").value(org.hamcrest.Matchers.contains("ESSENTIAL")));
        // reads back with splits; children inherit "excluded from habits"
        mockMvc.perform(get("/api/v1/expenses/" + id).with(as(seed)))
            .andExpect(jsonPath("$.splits", hasSize(2)));
        assertThat(splitsOf(seed))
            .hasSize(2).allMatch(Expense::isExcludedFromHabits).allMatch(e -> e.getOrigin() == ExpenseOrigin.SPLIT);

        split(seed, id, "[" + part("50.00", GROCERIES) + "," + part("50.00", DINING_OUT) + "]")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("ALREADY_SPLIT")));
        patchJson(seed, id, "{\"amount\":{\"amount\":\"90.00\",\"currency\":\"INR\"}}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("ALREADY_SPLIT")));
        // excluded flag on the parent cascades to the parts
        patchJson(seed, id, "{\"excludedFromHabits\":false}").andExpect(status().isOk());
        assertThat(splitsOf(seed))
            .noneMatch(Expense::isExcludedFromHabits);

        mockMvc.perform(delete("/api/v1/expenses/" + id + "/split").with(as(seed)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.splits").doesNotExist());
        assertThat(splitsOf(seed)).isEmpty();
        // undoing again is harmless, and it can be re-split
        mockMvc.perform(delete("/api/v1/expenses/" + id + "/split").with(as(seed))).andExpect(status().isOk());
        split(seed, id, "[" + part("50.00", GROCERIES) + "," + part("50.00", DINING_OUT) + "]").andExpect(status().isOk());
    }

    @Test
    void rejectsBadSplits() throws Exception {
        Seed seed = seedUser();
        Seed other = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, "");

        split(seed, id, "[" + part("40.00", GROCERIES) + "," + part("59.99", DINING_OUT) + "]")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", equalTo("SPLIT_SUM_MISMATCH")));
        split(seed, id, "[" + part("100.00", GROCERIES) + "]").andExpect(status().isBadRequest());
        split(seed, id, "[" + part("100.00", GROCERIES) + "," + part("0.00", GROCERIES) + "]")
            .andExpect(status().isBadRequest());
        split(seed, id, "[" + part("50.00", GROCERIES) + "," + part("50.00", UUID.randomUUID()) + "]")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", equalTo("INVALID_CATEGORY")));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            many.append(i > 0 ? "," : "").append(part("1.00", GROCERIES));
        }
        split(seed, id, many + "]").andExpect(status().isBadRequest());
        split(other, id, "[" + part("50.00", GROCERIES) + "," + part("50.00", DINING_OUT) + "]")
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/expenses/" + id).with(as(seed))).andExpect(jsonPath("$.splits").doesNotExist());
    }

    @Test
    void aSplitPartCannotBeSplitOrUnsplit() throws Exception {
        Seed seed = seedUser();
        String id = createId(seed, "2026-09-15", "100.00", FOOD, "");
        split(seed, id, "[" + part("40.00", GROCERIES) + "," + part("60.00", DINING_OUT) + "]");
        String child = splitsOf(seed).get(0).getId().toString();

        split(seed, child, "[" + part("20.00", GROCERIES) + "," + part("20.00", DINING_OUT) + "]")
            .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/expenses/" + child + "/split").with(as(seed))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/expenses/" + child).with(as(seed))).andExpect(status().isConflict());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/expenses")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
