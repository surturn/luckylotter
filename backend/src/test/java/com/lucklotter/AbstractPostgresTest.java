package com.lucklotter;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for persistence tests. Runs against a real Postgres 16 with the Flyway
 * migrations applied — never an in-memory substitute.
 *
 * <p>That is deliberate: most of this schema's guarantees are Postgres-specific
 * and invisible to H2 — the partial unique index behind "one active flag per
 * customer" (FR-8), the {@code CHECK} constraints that keep offer status and
 * {@code failure_code} in agreement, and {@code TIMESTAMPTZ} semantics. A test
 * on a substitute database would pass while the real one rejects the write.
 *
 * <h2>Where the database comes from</h2>
 *
 * Two modes, chosen by the {@code TEST_DB_URL} environment variable:
 *
 * <ul>
 *   <li><b>Unset</b> — Testcontainers starts one. This is the normal path on a
 *       machine with a JDK and a reachable Docker daemon, and the path CI
 *       should take.</li>
 *   <li><b>Set</b> — that JDBC URL is used as-is, and no container is started.
 *       This exists because the build currently runs <em>inside</em> a Maven
 *       container (no local JDK/Maven), where Docker Desktop's mounted socket
 *       is a stub the Docker API rejects, so Testcontainers cannot start
 *       anything. See {@code README}/build notes for the paired
 *       {@code docker run} invocation.</li>
 * </ul>
 *
 * Either way it must be a <em>disposable</em> database: Flyway migrates it on
 * context startup, and tests are only isolated from each other by transaction
 * rollback. Never point {@code TEST_DB_URL} at a database whose contents
 * matter.
 *
 * <p>When Testcontainers is used, the container is a JVM-wide singleton started
 * on first use rather than a {@code @Container} per class, so adding test
 * classes doesn't add container startups. It is never stopped — Testcontainers'
 * Ryuk sidecar reaps it when the JVM exits.
 *
 * <p>Subclasses inherit {@link DataJpaTest}, so each test method runs in a
 * transaction that is rolled back afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class
AbstractPostgresTest {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");
    private static final String EXTERNAL_USER =
            System.getenv().getOrDefault("TEST_DB_USER", "lucklotter");
    private static final String EXTERNAL_PASSWORD =
            System.getenv().getOrDefault("TEST_DB_PASSWORD", "lucklotter");

    private static final String JDBC_URL;
    private static final String USERNAME;
    private static final String PASSWORD;

    static {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            JDBC_URL = EXTERNAL_URL;
            USERNAME = EXTERNAL_USER;
            PASSWORD = EXTERNAL_PASSWORD;
        } else {
            // Same major version as docker-compose.yml, so tests and the dev
            // stack can't disagree about constraint or type behaviour.
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("lucklotter_test")
                    .withUsername("lucklotter")
                    .withPassword("lucklotter");
            postgres.start();
            JDBC_URL = postgres.getJdbcUrl();
            USERNAME = postgres.getUsername();
            PASSWORD = postgres.getPassword();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }
}
