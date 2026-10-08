package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcReservationGenerationsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JdbcReservationGenerations generations = new JdbcReservationGenerations(jdbc, transactions);

    @Test
    void aLoanWithoutCompensationsIsAtGenerationZero() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("LOAN-G1"))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("LOAN-G2"))).thenReturn(List.of(2));

        assertThat(generations.current(LoanId.of("LOAN-G1"))).isZero();
        assertThat(generations.current(LoanId.of("LOAN-G2"))).isEqualTo(2);
    }

    @Test
    void beginningACompensationAdvancesTheGenerationOnlyFromAnOutstandingReservationInItsOwnTransaction() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.update(startsWith("insert into credit_reservation_generation"), eq("LOAN-G3"), eq(3), eq(2), eq(2)))
            .thenReturn(1);

        assertThat(generations.beginCompensation(LoanId.of("LOAN-G3"), 2)).isTrue();
        assertThat(generations.beginCompensation(LoanId.of("LOAN-G3b"), 0)).isFalse();

        verify(transactions, org.mockito.Mockito.times(2)).getTransaction(argThat(definition ->
            definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(jdbc).update(argThat((String sql) -> sql.contains("on conflict (loan_id) do update")
                && sql.contains("reservation_state in ('RESERVING', 'RESERVED', 'UNCONFIRMED')")
                && sql.contains("pending_compensation is null")),
            eq("LOAN-G3"), eq(3), eq(2), eq(2));
    }

    @Test
    void theCancellationAndTheSweepCompensateOnlyAReservationRecordedAsAccepted() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.update(contains("reservation_state = 'RESERVED'"), eq(1), eq(0), eq("LOAN-G7"), eq(0))).thenReturn(1);

        assertThat(generations.beginCompensationOfAccepted(LoanId.of("LOAN-G7"), 0)).isTrue();
        assertThat(generations.beginCompensationOfAccepted(LoanId.of("LOAN-G8"), 0)).isFalse();
    }

    @Test
    void aReservationIsRecordedBeforeItIsSentAndRefusedWhenTheRowMovedOn() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(contains("from credit_reservation_generation where loan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ReservationGenerations.Reservation>>any(),
                eq("LOAN-G9")))
            .thenReturn(List.of(row("LOAN-G9", 0, ReservationGenerations.State.RESERVING, null)));
        when(jdbc.query(contains("from credit_reservation_generation where loan_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ReservationGenerations.Reservation>>any(),
                eq("LOAN-G10")))
            .thenReturn(List.of(row("LOAN-G10", 1, null, 0)));

        generations.beginReservation(LoanId.of("LOAN-G9"), 0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generations.beginReservation(LoanId.of("LOAN-G10"), 0))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("generation 1");

        verify(jdbc).update(argThat((String sql) -> sql.startsWith("insert into credit_reservation_generation")
                && sql.contains("'RESERVING'")), eq("LOAN-G9"), eq(0));
    }

    @Test
    void theAnswerToAReserveIsRecordedForItsGeneration() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        generations.reservationAnswered(LoanId.of("LOAN-G11"), 0, true);
        generations.reservationAnswered(LoanId.of("LOAN-G12"), 1, false);

        verify(jdbc).update(contains("set reservation_state = 'RESERVED'"), eq("LOAN-G11"), eq(0));
        verify(jdbc).update(contains("set reservation_state = null"), eq("LOAN-G12"), eq(1));
    }

    @Test
    void theDisbursementMarksTheReservationUsedInItsOwnTransactionUnlessItWasReleased() {
        when(jdbc.update(contains("set reservation_state = 'USED'"), eq("LOAN-G13"))).thenReturn(1);
        when(jdbc.update(contains("set reservation_state = 'USED'"), eq("LOAN-G14"))).thenReturn(0);
        when(jdbc.update(contains("set reservation_state = 'USED'"), eq("LOAN-G15"))).thenReturn(0);
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), eq("LOAN-G14"))).thenReturn(1);
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), eq("LOAN-G15"))).thenReturn(0);

        assertThat(generations.markUsed(LoanId.of("LOAN-G13"))).isTrue();
        assertThat(generations.markUsed(LoanId.of("LOAN-G14"))).isFalse();
        assertThat(generations.markUsed(LoanId.of("LOAN-G15"))).isTrue();
        org.mockito.Mockito.verifyNoInteractions(transactions);
    }

    @Test
    void theSweepReadsOnlyNeverDisbursedLoansAndCountsWithoutIdentifiers() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(contains("join loan"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(contains("reservation_state = 'UNCONFIRMED'"), eq(Long.class))).thenReturn(1L);
        when(jdbc.update(contains("set reservation_state = 'UNCONFIRMED'"), eq("LOAN-G16"), eq(0))).thenReturn(1);

        generations.unresolved(java.time.Instant.parse("2026-10-08T06:00:00Z"), 50);

        verify(jdbc).query(argThat((String sql) -> sql.contains(
                "l.status in ('CREATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED')")),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ReservationGenerations.Unresolved>>any(),
            eq(java.sql.Timestamp.from(java.time.Instant.parse("2026-10-08T06:00:00Z"))), eq(50));
        assertThat(generations.countPending()).isEqualTo(3);
        assertThat(generations.countUnconfirmed()).isEqualTo(1);
        assertThat(generations.markUnconfirmed(LoanId.of("LOAN-G16"), 0)).isTrue();
        assertThat(generations.markUnconfirmed(LoanId.of("LOAN-G17"), 0)).isFalse();
    }

    @Test
    void aRefusedReleaseIsRecordedOnItsPendingCompensationAndCounted() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(contains("release_refused_code"), eq(String.class), eq("LOAN-G18")))
            .thenReturn(List.of("RELEASE_EXCEEDS_RESERVATION"));
        when(jdbc.queryForList(contains("release_refused_code"), eq(String.class), eq("LOAN-G19"))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("release_refused_code is not null"), eq(Long.class))).thenReturn(2L);

        generations.releaseRefused(LoanId.of("LOAN-G18"), 0, "RELEASE_EXCEEDS_RESERVATION");

        verify(jdbc).update(contains("set release_refused_code = ?"), eq("RELEASE_EXCEEDS_RESERVATION"), eq("LOAN-G18"), eq(0));
        assertThat(generations.releaseRefusedReason(LoanId.of("LOAN-G18"))).contains("RELEASE_EXCEEDS_RESERVATION");
        assertThat(generations.releaseRefusedReason(LoanId.of("LOAN-G19"))).isEmpty();
        assertThat(generations.countReleaseRefused()).isEqualTo(2);
    }

    private static ReservationGenerations.Reservation row(String loanId, int generation,
                                                          ReservationGenerations.State state, Integer pending) {
        return new ReservationGenerations.Reservation(LoanId.of(loanId), generation, state, pending, java.time.Instant.EPOCH);
    }

    @Test
    void confirmingACompensationClearsOnlyThatGenerationInItsOwnTransaction() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        generations.compensationDone(LoanId.of("LOAN-G4"), 1);

        verify(transactions).getTransaction(argThat(definition ->
            definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(jdbc).update(contains("set pending_compensation = null"), eq("LOAN-G4"), eq(1));
    }

    @Test
    void pendingCompensationIsReadFromTheRow() {
        when(jdbc.queryForList(contains("pending_compensation"), eq(Integer.class), eq("LOAN-G5"))).thenReturn(List.of(0));
        when(jdbc.queryForList(contains("pending_compensation"), eq(Integer.class), eq("LOAN-G6"))).thenReturn(List.of());

        assertThat(generations.pendingCompensation(LoanId.of("LOAN-G5"))).hasValue(0);
        assertThat(generations.pendingCompensation(LoanId.of("LOAN-G6"))).isEmpty();
    }
}
