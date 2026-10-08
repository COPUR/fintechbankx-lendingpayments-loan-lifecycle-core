package com.bank.loan;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml) and the tests fail there
 * without it; locally either set TEST_DB_URL or have Docker running for
 * Testcontainers, otherwise the integration tests are skipped.
 */
final class PostgresTestDatabase {

    private static PostgreSQLContainer<?> container;

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
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "loan_test"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "loan_test"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_ln_loan_lifecycle_test")
                .withUsername("loan_test")
                .withPassword("loan_test");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
