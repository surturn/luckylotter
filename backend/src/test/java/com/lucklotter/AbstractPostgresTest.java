package com.lucklotter;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
 * <p>The container is a JVM-wide singleton started on first use rather than a
 * {@code @Container} per class, so adding test classes doesn't add container
 * startups. It is never stopped — Testcontainers' Ryuk sidecar reaps it when
 * the JVM exits.
 *
 * <p>Docker discovery depends on {@code src/test/resources/docker-java.properties}
 * pinning the Engine API version; see that file before changing anything about
 * how the container is created.
 *
 * <p>Subclasses inherit {@link DataJpaTest}, so each test method runs in a
 * transaction that is rolled back afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class
AbstractPostgresTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerDatasourceProperties(registry);
    }
}
