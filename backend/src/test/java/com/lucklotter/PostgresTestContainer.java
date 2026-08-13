package com.lucklotter;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The single Postgres container every test in the JVM shares.
 *
 * <p>Extracted so the persistence slice ({@link AbstractPostgresTest}) and the
 * full-context web tests ({@link AbstractWebTest}) point at the same database
 * instead of starting one each. Two containers would double the slowest part of
 * the suite and, worse, let the two tiers disagree about the schema they were
 * tested against.
 *
 * <p>Started on first class load and never stopped — Testcontainers' Ryuk
 * sidecar reaps it when the JVM exits. Docker discovery depends on
 * {@code src/test/resources/docker-java.properties} pinning the Engine API
 * version; read that file before changing anything here.
 */
public final class PostgresTestContainer {

    // Same major version as docker-compose.yml, so tests and the dev stack
    // can't disagree about constraint or type behaviour.
    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("lucklotter_test")
                    .withUsername("lucklotter")
                    .withPassword("lucklotter");

    static {
        INSTANCE.start();
    }

    private PostgresTestContainer() {
    }

    public static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", INSTANCE::getUsername);
        registry.add("spring.datasource.password", INSTANCE::getPassword);
    }
}
