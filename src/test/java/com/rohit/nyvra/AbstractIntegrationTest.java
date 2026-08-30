package com.rohit.nyvra;

import com.rohit.nyvra.config.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb-ha:pg16")
            .asCompatibleSubstituteFor("postgres"));
}
