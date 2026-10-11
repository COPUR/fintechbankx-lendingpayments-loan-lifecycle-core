package com.bank.loan;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml) and the tests fail there
 * without it; locally either set TEST_DB_URL or have Docker running for
 * Testcontainers, otherwise the integration tests are skipped.
 *
 * Runs the service with the two roles of production: Flyway as the schema
 * owner (the database's test user, through DB_MIGRATION_USERNAME / _PASSWORD,
 * in-process here, a Helm hook Job when deployed) and the application as a
 * separate runtime role (DB_USERNAME) that has only the grants of V4. The
 * owner needs CREATEROLE to create that role.
 */
final class PostgresTestDatabase {

    static final String RUNTIME_ROLE = "loan_runtime_it";
    static final String RUNTIME_PASSWORD = "loan_runtime_it";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    private PostgresTestDatabase() {
    }

    /**
     * Call from a static @BeforeAll. In CI (CI=true) a missing TEST_DB_URL
     * fails the build, so the integration tests can never be skipped silently;
     * elsewhere the class is skipped without a database.
     */
    static void assumeAvailable() {
        requireInCi(System.getenv("CI"), System.getenv("TEST_DB_URL"));
        Assumptions.assumeTrue(hasExternalDatabase() || DockerClientFactory.instance().isDockerAvailable(),
            "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    static void requireInCi(String ci, String testDbUrl) {
        if ("true".equalsIgnoreCase(ci) && (testDbUrl == null || testDbUrl.isBlank())) {
            throw new IllegalStateException(
                "CI=true but TEST_DB_URL is not set: integration tests must run against PostgreSQL in CI");
        }
    }

    private static boolean hasExternalDatabase() {
        String value = System.getenv("TEST_DB_URL");
        return value != null && !value.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", () -> url);
        // Runtime role: what the pods connect as.
        registry.add("DB_USERNAME", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        // Schema owner: what Flyway connects as.
        registry.add("DB_MIGRATION_USERNAME", () -> ownerUser);
        registry.add("DB_MIGRATION_PASSWORD", () -> ownerPassword);
    }

    static synchronized String url() {
        start();
        return url;
    }

    static synchronized String ownerUser() {
        start();
        return ownerUser;
    }

    static synchronized String ownerPassword() {
        start();
        return ownerPassword;
    }

    /** The schema owner's connection, for test set-up the runtime role may not do. */
    static synchronized JdbcTemplate owner() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
    }

    /** A plain connection as the runtime role, outside the application. */
    static synchronized JdbcTemplate runtime() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static void start() {
        if (url != null) {
            return;
        }
        String external = System.getenv("TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            ownerUser = env("TEST_DB_USERNAME", "loan_test");
            ownerPassword = env("TEST_DB_PASSWORD", "loan_test");
        } else {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_ln_loan_lifecycle_test")
                .withUsername("loan_test")
                .withPassword("loan_test");
            container.start();
            url = container.getJdbcUrl();
            ownerUser = container.getUsername();
            ownerPassword = container.getPassword();
        }
        new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).execute("""
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = '%1$s') then
                    create role %1$s login password '%2$s';
                end if;
            end $$
            """.formatted(RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
