package com.rohit.nyvra.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Verifies {@link UserIdMdcFilter} puts the JWT subject — never email/name — into MDC. */
class UserIdMdcFilterTest {

    private final UserIdMdcFilter filter = new UserIdMdcFilter();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void putsSubjectNotEmailOrNameIntoMdc() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token-value")
            .header("alg", "none")
            .subject("subject-under-test")
            .claim("email", "leaked@nyvra.local")
            .claim("name", "Leaked Name")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        boolean[] ranInsideChain = {false};
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            assertThat(MDC.get("userId")).isEqualTo("subject-under-test");
            assertThat(MDC.getCopyOfContextMap())
                .as("no MDC value may ever contain the email or display name")
                .allSatisfy((key, value) -> assertThat(value)
                    .doesNotContain("leaked@nyvra.local")
                    .doesNotContain("Leaked Name"));
            ranInsideChain[0] = true;
        });

        assertThat(ranInsideChain[0]).isTrue();
        assertThat(MDC.get("userId")).as("MDC must be cleared once the request completes").isNull();
    }

    @Test
    void noOpWhenUnauthenticated() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
            (req, res) -> assertThat(MDC.get("userId")).isNull());
    }
}
