package com.bank.loan;

import com.bank.loan.domain.LoanId;
import com.bank.loan.infrastructure.external.JdbcReservationGenerations;
import com.bank.loan.infrastructure.external.ReservationGenerations;
import com.bank.loan.infrastructure.external.ReservationGenerations.State;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compare-and-set rules of {@link JdbcReservationGenerations} against
 * PostgreSQL, as the runtime role (V4 grants).
 *
 * <p>Customer CRC decision 2026-10-10: the recovery sweep releases a reserve
 * that was sent but never answered by its reference once the grace period
 * has passed. That is only safe if no reserve of the same generation is in
 * flight when the release is sent: a disbursement retry that re-sends the
 * reserve refreshes the row first, so the sweep's intent no longer matches,
 * and once the sweep has recorded its intent the retry cannot re-send the
 * old generation's reserve.
 */
class ReservationGenerationsPostgresIT {

    private static final String SCHEMA = "sc_ln_loan_lifecycle";

    private static JdbcTemplate owner;
    private static ReservationGenerations generations;

    @BeforeAll
    static void migrated() {
        PostgresTestDatabase.assumeAvailable();
        assertThat(LoanLifecycleApplication.run("migrate",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword())).isZero();
        owner = PostgresTestDatabase.owner();
        DriverManagerDataSource runtime = new DriverManagerDataSource(PostgresTestDatabase.url(),
            PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD);
        runtime.setSchema(SCHEMA);
        generations = new JdbcReservationGenerations(new JdbcTemplate(runtime), new DataSourceTransactionManager(runtime));
    }

    @BeforeEach
    void clean() {
        owner.update("delete from " + SCHEMA + ".credit_reservation_generation where loan_id like 'LOAN-PGR-%'");
        owner.update("delete from " + SCHEMA + ".loan where loan_id like 'LOAN-PGR-%'");
    }

    @Test
    void aStaleUnansweredReserveIsTakenOverForItsReleaseOnce() {
        Instant before = Instant.now().minusSeconds(600);
        row("LOAN-PGR-STALE", 0, "RESERVING", "1 hour");
        row("LOAN-PGR-LEGACY", 2, "UNCONFIRMED", "1 hour");
        row("LOAN-PGR-FRESH", 0, "RESERVING", "1 second");
        row("LOAN-PGR-ACCEPTED", 0, "RESERVED", "1 hour");

        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-STALE"), 0, before)).isTrue();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-STALE"), 0, before)).isFalse();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-LEGACY"), 2, before)).isTrue();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-FRESH"), 0, before)).isFalse();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-ACCEPTED"), 0, before)).isFalse();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-LEGACY"), 1, before)).isFalse();

        assertThat(generations.find(LoanId.of("LOAN-PGR-STALE")).orElseThrow())
            .extracting(ReservationGenerations.Reservation::generation, ReservationGenerations.Reservation::state,
                ReservationGenerations.Reservation::pendingCompensation)
            .containsExactly(1, null, 0);
        assertThat(generations.pendingCompensation(LoanId.of("LOAN-PGR-LEGACY"))).hasValue(2);
        assertThat(generations.find(LoanId.of("LOAN-PGR-FRESH")).orElseThrow().state()).isEqualTo(State.RESERVING);
    }

    @Test
    void aDisbursementRetryRefreshesTheRowSoTheSweepNoLongerTakesItOver() {
        Instant before = Instant.now().minusSeconds(600);
        row("LOAN-PGR-RETRY", 0, "RESERVING", "1 hour");
        row("LOAN-PGR-RETRY-LEGACY", 0, "UNCONFIRMED", "1 hour");

        generations.beginReservation(LoanId.of("LOAN-PGR-RETRY"), 0);
        generations.beginReservation(LoanId.of("LOAN-PGR-RETRY-LEGACY"), 0);

        assertThat(generations.find(LoanId.of("LOAN-PGR-RETRY-LEGACY")).orElseThrow().state()).isEqualTo(State.RESERVING);
        assertThat(generations.find(LoanId.of("LOAN-PGR-RETRY")).orElseThrow().updatedAt()).isAfter(before);
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-RETRY"), 0, before)).isFalse();
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-RETRY-LEGACY"), 0, before)).isFalse();
    }

    @Test
    void onceTheSweepRecordedItsIntentTheOldReserveIsNotSentAgain() {
        row("LOAN-PGR-TAKEN", 0, "RESERVING", "1 hour");
        assertThat(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-PGR-TAKEN"), 0,
            Instant.now().minusSeconds(600))).isTrue();

        assertThatThrownBy(() -> generations.beginReservation(LoanId.of("LOAN-PGR-TAKEN"), 0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("generation 1");
    }

    @Test
    void theSweepSeesUnansweredAcceptedAndPendingRowsButNotRefusedOnes() {
        loan("LOAN-PGR-U1", "APPROVED");
        loan("LOAN-PGR-U2", "CANCELLED");
        loan("LOAN-PGR-U3", "REJECTED");
        loan("LOAN-PGR-U4", "CANCELLED");
        loan("LOAN-PGR-U5", "DISBURSED");
        loan("LOAN-PGR-U6", "CANCELLED");
        row("LOAN-PGR-U1", 0, "RESERVING", "1 hour");
        row("LOAN-PGR-U2", 0, "UNCONFIRMED", "1 hour");
        row("LOAN-PGR-U3", 0, "RESERVED", "1 hour");
        row("LOAN-PGR-U4", 1, null, "1 hour");
        owner.update("update " + SCHEMA + ".credit_reservation_generation set pending_compensation = 0 where loan_id = 'LOAN-PGR-U4'");
        row("LOAN-PGR-U5", 0, "RESERVING", "1 hour");
        row("LOAN-PGR-U6", 1, null, "1 hour");
        owner.update("update " + SCHEMA + ".credit_reservation_generation set pending_compensation = 0,"
            + " release_refused_code = 'RELEASE_EXCEEDS_RESERVATION' where loan_id = 'LOAN-PGR-U6'");

        List<String> seen = generations.unresolved(Instant.now().minusSeconds(600), 50).stream()
            .map(row -> row.reservation().loanId().getValue())
            .filter(id -> id.startsWith("LOAN-PGR-"))
            .toList();

        assertThat(seen).containsExactlyInAnyOrder("LOAN-PGR-U1", "LOAN-PGR-U2", "LOAN-PGR-U3", "LOAN-PGR-U4");
    }

    private static void row(String loanId, int generation, String state, String age) {
        loan(loanId, "APPROVED");
        owner.update("insert into " + SCHEMA + ".credit_reservation_generation"
            + " (loan_id, generation, reservation_state, updated_at) values (?, ?, ?, now() - interval '" + age + "')",
            loanId, generation, state);
    }

    private static void loan(String loanId, String status) {
        owner.update("insert into " + SCHEMA + ".loan (loan_id, customer_id, principal_amount, currency, annual_interest_rate,"
            + " term_months, status, application_date, outstanding_balance, created_at, updated_at)"
            + " values (?, 'CUST-PGR-1', 12000.0000, 'AED', 6.0, 12, ?, current_date, 12000.0000, now(), now())"
            + " on conflict (loan_id) do nothing",
            loanId, status);
    }
}
