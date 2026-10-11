package com.bank.loan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed migration step: the Helm pre-install/pre-upgrade Job runs the
 * image with the argument "migrate". It runs Flyway as the schema owner,
 * grants the runtime role, starts no web server, no Kafka and no security,
 * and exits 0. Runs against a scratch schema so the shared one is untouched.
 */
class DatabaseMigrationIT {

    private static final String SCHEMA = "sc_ln_migration_job_it";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @AfterEach
    void dropScratchSchema() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void migrateRunsFlywayAsTheOwnerGrantsTheRuntimeRoleAndExits() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
        PostgresTestDatabase.runtime().queryForObject("select 1", Integer.class); // creates the runtime role

        int exitCode = LoanLifecycleApplication.run(
            "migrate",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
            "--spring.flyway.schemas=" + SCHEMA,
            "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isZero();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(owner.queryForObject(
            "select count(*) from " + SCHEMA + ".flyway_schema_history where success", Integer.class)).isGreaterThanOrEqualTo(4);
        assertThat(owner.queryForObject(
            "select tableowner from pg_tables where schemaname = ? and tablename = 'loan'", String.class, SCHEMA))
            .isEqualTo(PostgresTestDatabase.ownerUser());
        assertThat(owner.queryForObject(
            "select has_table_privilege(?, ?, 'INSERT')", Boolean.class,
            PostgresTestDatabase.RUNTIME_ROLE, SCHEMA + ".loan")).isTrue();
        assertThat(owner.queryForObject(
            "select has_schema_privilege(?, ?, 'CREATE')", Boolean.class,
            PostgresTestDatabase.RUNTIME_ROLE, SCHEMA)).isFalse();
    }

    @Test
    void migrateFailsWithANonZeroExitCodeWhenTheDatabaseRefuses() {
        int exitCode = LoanLifecycleApplication.run(
            "migrate",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=wrong-password",
            "--spring.flyway.schemas=" + SCHEMA,
            "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isNotZero();
    }

    /**
     * V11 (ADR-019 section 8, one topic per aggregate): rows written before it,
     * with whatever topic the old per-event mapping stored, now name the
     * aggregate topic evt.ln.loan.v1 that the relay sends them to, and the
     * new fapi_interaction_id column is empty for them (no header invented).
     */
    @Test
    void v11PointsEveryStoredRowAtTheAggregateTopic() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
        Flyway upToV10 = flyway("10");
        upToV10.migrate();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("insert into " + SCHEMA + ".outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version,"
            + " event_type, topic, payload, correlation_id, occurred_at)"
            + " values ('5c1e2c4a-6a7b-4c1d-9e8f-0a1b2c3d4e5f', 'Loan', 'LOAN-V11', 0, 'Lending.Loan.Created.v1',"
            + " 'evt.ln.loan.written-before-v11', '{}'::jsonb, 'corr-v11', now())");

        flyway("latest").migrate();

        Map<String, Object> row = owner.queryForMap("select topic, fapi_interaction_id from " + SCHEMA
            + ".outbox_event where aggregate_id = 'LOAN-V11'");
        assertThat(row.get("topic")).isEqualTo("evt.ln.loan.v1");
        assertThat(row.get("fapi_interaction_id")).isNull();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(PostgresTestDatabase.url(), PostgresTestDatabase.ownerUser(), PostgresTestDatabase.ownerPassword())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .placeholders(Map.of("runtime_role", PostgresTestDatabase.ownerUser()))
            .target(target)
            .load();
    }
}
