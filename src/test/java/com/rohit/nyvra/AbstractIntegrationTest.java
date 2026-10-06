package com.rohit.nyvra;

import java.security.SecureRandom;
import java.util.Base64;

import com.rohit.nyvra.config.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need the full application context and a real database. Runs Postgres via
 * Testcontainers on the same image as {@code docker-compose.yml} and CI
 * ({@code timescale/timescaledb-ha:pg16}) rather than plain {@code postgres}, so hypertable DDL
 * (once migrations add it) behaves the same in tests as it does everywhere else.
 *
 * <p>{@code @ServiceConnection} wires the container's JDBC connection straight into the Spring
 * context — no {@code NYVRA_DB_URL}/{@code NYVRA_DB_USERNAME}/{@code NYVRA_DB_PASSWORD} needed.
 * {@link TestSecurityConfig} supplies a stub {@code JwtDecoder} so context startup doesn't need a
 * reachable Keycloak; authenticate requests with
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} instead of a real token.
 *
 * <p>One container per JVM: it is deliberately not a JUnit {@code @Container}, whose per-class lifecycle
 * would stop it after the first test class while Spring's cached context still points at its port.
 * It starts once in the static initialiser; Testcontainers' reaper removes it when the JVM exits.
 *
 * <p>Field-encryption and blind-index keys are generated randomly once per JVM, so no key material is
 * ever committed (not even a test one) and the cached Spring context is shared across test classes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb-ha:pg16")
            .asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    private static final String FIELD_ENCRYPTION_KEY = randomKey();
    private static final String BLIND_INDEX_KEY = randomKey();

    @DynamicPropertySource
    static void cryptoKeys(DynamicPropertyRegistry registry) {
        registry.add("nyvra.crypto.field-encryption-key", () -> FIELD_ENCRYPTION_KEY);
        registry.add("nyvra.crypto.blind-index-key", () -> BLIND_INDEX_KEY);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
