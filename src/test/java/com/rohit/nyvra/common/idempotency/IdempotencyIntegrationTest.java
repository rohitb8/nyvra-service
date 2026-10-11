package com.rohit.nyvra.common.idempotency;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.accounts.FinancialAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end checks of {@code Idempotency-Key} handling against real Redis, using
 * {@code POST /api/v1/accounts} as the creating endpoint, plus the generic error codes the
 * filter chain and MVC produce.
 */
@AutoConfigureMockMvc
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    /** Valid account body; {@code %s} is the label. */
    private static final String ACCOUNT_BODY = """
        {"type":"SAVINGS","label":"%s","institution":"HDFC Bank","maskedNumber":"XXXX1234",
         "currentBalance":{"amount":"100.00","currency":"INR"}}""";

    /** Drives the API through the full filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to count the accounts a user ends up with. */
    @Autowired
    private FinancialAccountRepository accounts;

    /**
     * Authenticates a request as the given token subject.
     *
     * @param subject the JWT subject
     * @return the request post-processor
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /**
     * Returns a fresh token subject so tests never share data.
     *
     * @return a unique subject
     */
    private static String newSubject() {
        return "idem-" + UUID.randomUUID();
    }

    /** A repeat with the same key and body replays the first response and creates nothing new. */
    @Test
    void sameKeyAndBodyReplaysTheFirstResponse() throws Exception {
        String subject = newSubject();
        String key = UUID.randomUUID().toString();
        String body = ACCOUNT_BODY.formatted("Once");

        String first = mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(header().exists("Location"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));

        org.assertj.core.api.Assertions.assertThat(
            accounts.findAll().stream().filter(a -> "Once".equals(a.getLabel())).count()).isEqualTo(1);
    }

    /** The same key with a different body is rejected as {@code IDEMPOTENCY_KEY_REUSED}. */
    @Test
    void sameKeyWithDifferentBodyIs409() throws Exception {
        String subject = newSubject();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("A")))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("B")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("IDEMPOTENCY_KEY_REUSED")));
    }

    /** Keys are scoped per user: another user reusing the key creates their own resource. */
    @Test
    void keysAreScopedPerUser() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = ACCOUNT_BODY.formatted("Shared");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/accounts").with(as(newSubject())).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        }
    }

    /** A failed attempt frees the key, so a corrected retry with it succeeds. */
    @Test
    void failedRequestDoesNotConsumeTheKey() throws Exception {
        String subject = newSubject();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_FAILED")));

        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("Fixed")))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    /** A key that is not a UUID is a 400. */
    @Test
    void nonUuidKeyIs400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(as(newSubject())).header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("X")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code", equalTo("BAD_REQUEST")));
    }

    /** Without the header every POST creates a new resource, as before. */
    @Test
    void requestsWithoutTheHeaderAreNotDeduplicated() throws Exception {
        String subject = newSubject();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/accounts").with(as(subject))
                    .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("Plain")))
                .andExpect(status().isCreated());
        }
    }

    /** A missing token yields 401 with the {@code UNAUTHENTICATED} code, even with the header set. */
    @Test
    void unauthenticatedRequestIs401WithCode() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY.formatted("X")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", equalTo("UNAUTHENTICATED")));
    }
}
