package com.bank.loan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loan #14 review (minor): TlsEnforcementConfiguration was registered by the
 * service's component scan only, so the Helm migration Job's context
 * (LoanLifecycleApplication "migrate", DataSource and Flyway auto-configuration
 * alone) ran no Java check on its DB_URL. The migrate context imports it
 * explicitly: a datasource URL without sslmode=verify-full stops the Job before
 * Flyway connects, and, with no Kafka client there, only the database is checked.
 */
class MigrationContextTlsTest {

    private static final String VERIFY_FULL = "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle"
        + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REQUIRE = "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle?sslmode=require";

    private final ApplicationContextRunner migrate = new ApplicationContextRunner()
        .withUserConfiguration(LoanLifecycleApplication.DatabaseMigration.class)
        // Flyway would connect at start-up; the TLS assertion runs before any bean exists, so it never gets that far
        .withPropertyValues("spring.flyway.enabled=false");

    @Test
    void theMigrateContextRefusesADatasourceUrlWithoutVerifyFull() {
        migrate.withPropertyValues(REQUIRE).run(context -> {
            assertThat(context).hasFailed();
            Throwable cause = context.getStartupFailure();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("fintechbankx.tls.enforce");
        });
    }

    @Test
    void theMigrateContextChecksOnlyTheDatabase() {
        // no Kafka client in the migrate context: a PLAINTEXT protocol is not its concern
        migrate.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
