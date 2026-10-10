package com.rohit.nyvra.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of {@code /api/v1/categories}: the tree, creating, renaming and deleting custom categories, and
 * the rules around system categories, ownership and categories still in use.
 */
@AutoConfigureMockMvc
class CategoryControllerIntegrationTest extends AbstractIntegrationTest {

    /** Seeded in V4.1: "Food & dining" (ESSENTIAL), top level. */
    private static final UUID FOOD = UUID.fromString("40a8ed3d-45f2-50fe-8806-e94e6a8eb931");

    /** Seeded in V4.1: "Groceries", a child of {@link #FOOD}. */
    private static final UUID GROCERIES = UUID.fromString("566d0670-1558-5400-b5d1-5889e0dd8af9");

    /** Seeded in V4.1: "Shopping", top level. */
    private static final UUID SHOPPING = UUID.fromString("777df330-9c34-5d70-856b-cc533d7b77f6");

    /** Issues requests against the full application. */
    @Autowired
    private MockMvc mockMvc;

    /** Creates the users the tests act as. */
    @Autowired
    private UserProfileRepository users;

    /** Used to make a category "in use". */
    @Autowired
    private CategorisationRuleRepository rules;

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
     * POSTs a category.
     *
     * @param seed   the caller
     * @param name   the category name
     * @param parent the parent id
     * @return the result, for assertions
     * @throws Exception on request failure
     */
    private ResultActions createCategory(Seed seed, String name, UUID parent) throws Exception {
        return mockMvc.perform(post("/api/v1/categories").with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"parentId\":\"%s\"}".formatted(name, parent)));
    }

    /**
     * Creates a category and returns its id.
     *
     * @param seed   the caller
     * @param name   the category name
     * @param parent the parent id
     * @return the new id
     * @throws Exception on request failure
     */
    private String createCategoryId(Seed seed, String name, UUID parent) throws Exception {
        String body = createCategory(seed, name, parent).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * The tree lists system categories with their children, and a user's custom category appears only for them.
     *
     * @throws Exception on request failure
     */
    @Test
    void treeShowsSystemCategoriesAndOnlyTheCallersCustomOnes() throws Exception {
        Seed alice = seedUser();
        Seed bob = seedUser();
        createCategory(alice, "Street food", FOOD).andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("http://localhost/api/v1/categories/")))
            .andExpect(jsonPath("$.name", equalTo("Street food")))
            .andExpect(jsonPath("$.parentId", equalTo(FOOD.toString())))
            .andExpect(jsonPath("$.system", equalTo(false)));

        String food = "$[?(@.id=='%s')]".formatted(FOOD);
        mockMvc.perform(get("/api/v1/categories").with(as(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem("Shopping")))
            .andExpect(jsonPath(food + ".system", hasItem(true)))
            .andExpect(jsonPath(food + ".children[*].name", hasItem("Groceries")))
            .andExpect(jsonPath(food + ".children[*].name", hasItem("Street food")));
        mockMvc.perform(get("/api/v1/categories").with(as(bob))).andExpect(status().isOk())
            .andExpect(jsonPath(food + ".children[*].name", not(hasItem("Street food"))));
    }

    /**
     * A category can only go under a top-level category the caller can see, with a name unused among its siblings.
     *
     * @throws Exception on request failure
     */
    @Test
    void createValidatesParentAndName() throws Exception {
        Seed seed = seedUser();
        createCategory(seed, "Street food", FOOD).andExpect(status().isCreated());

        createCategory(seed, "street FOOD", FOOD).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("CATEGORY_NAME_TAKEN")));
        createCategory(seed, "Groceries", FOOD).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("CATEGORY_NAME_TAKEN")));
        createCategory(seed, "Nested", GROCERIES).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("INVALID_PARENT_CATEGORY")));
        createCategory(seed, "Orphan", UUID.randomUUID()).andExpect(status().isNotFound());
        createCategory(seed, "   ", FOOD).andExpect(status().isBadRequest());
        createCategory(seed, "x".repeat(61), FOOD).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/categories").with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"No parent\"}")).andExpect(status().isBadRequest());
    }

    /**
     * Another user's custom category can't be used as a parent or touched: it looks missing.
     *
     * @throws Exception on request failure
     */
    @Test
    void anotherUsersCategoryLooksMissing() throws Exception {
        Seed alice = seedUser();
        Seed bob = seedUser();
        String aliceCategory = createCategoryId(alice, "Alice only", FOOD);

        mockMvc.perform(patch("/api/v1/categories/" + aliceCategory).with(as(bob)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Mine now\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/categories/" + aliceCategory).with(as(bob))).andExpect(status().isNotFound());
    }

    /**
     * A custom category can be renamed and given a default necessity; clashing names are refused.
     *
     * @throws Exception on request failure
     */
    @Test
    void renamesACustomCategoryAndChangesItsNecessity() throws Exception {
        Seed seed = seedUser();
        String id = createCategoryId(seed, "Street food", FOOD);
        createCategory(seed, "Takeaway", FOOD).andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/categories/" + id).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\" Chaat \",\"necessityDefault\":\"DISCRETIONARY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name", equalTo("Chaat")))
            .andExpect(jsonPath("$.necessityDefault", equalTo("DISCRETIONARY")));
        mockMvc.perform(patch("/api/v1/categories/" + id).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"takeaway\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("CATEGORY_NAME_TAKEN")));
        mockMvc.perform(patch("/api/v1/categories/" + id).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}")).andExpect(status().isBadRequest());
        // renaming to its own current name is not a clash
        mockMvc.perform(patch("/api/v1/categories/" + id).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Chaat\"}")).andExpect(status().isOk());
    }

    /**
     * System categories can be neither edited nor deleted.
     *
     * @throws Exception on request failure
     */
    @Test
    void systemCategoriesAreImmutable() throws Exception {
        Seed seed = seedUser();
        mockMvc.perform(patch("/api/v1/categories/" + FOOD).with(as(seed)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Hacked\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("SYSTEM_CATEGORY_IMMUTABLE")));
        mockMvc.perform(delete("/api/v1/categories/" + GROCERIES).with(as(seed)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("SYSTEM_CATEGORY_IMMUTABLE")));
    }

    /**
     * An unused custom category deletes; one a rule still targets is refused until the rule goes.
     *
     * @throws Exception on request failure
     */
    @Test
    void deletesACategoryOnlyWhenNothingUsesIt() throws Exception {
        Seed seed = seedUser();
        String free = createCategoryId(seed, "Unused", SHOPPING);
        mockMvc.perform(delete("/api/v1/categories/" + free).with(as(seed))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/categories").with(as(seed)))
            .andExpect(jsonPath("$[*].children[*].name", not(hasItem("Unused"))));

        String used = createCategoryId(seed, "Used", SHOPPING);
        CategorisationRule rule = rules.save(new CategorisationRule(seed.user().getId(), MatcherType.MERCHANT_REGEX,
            "shop", UUID.fromString(used), Necessity.DISCRETIONARY, 100));
        mockMvc.perform(delete("/api/v1/categories/" + used).with(as(seed)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code", equalTo("CATEGORY_IN_USE")));

        rules.delete(rule);
        mockMvc.perform(delete("/api/v1/categories/" + used).with(as(seed))).andExpect(status().isNoContent());
        assertThat(rules.findByUserId(seed.user().getId(), org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }

    /**
     * Without a token the endpoints answer 401.
     *
     * @throws Exception on request failure
     */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
    }
}
