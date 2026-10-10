package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditService.UnusedReservation;
import com.bank.loan.domain.port.out.CreditReservationNeedsOperatorException;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.infrastructure.external.ReservationGenerations.Reservation;
import com.bank.loan.infrastructure.external.ReservationGenerations.State;
import com.bank.loan.infrastructure.external.ReservationGenerations.Unresolved;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditReservationSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private static final CustomerId CUSTOMER = CustomerId.of("CUST-SWEEP-1");
    private static final Money PRINCIPAL = Money.aed(new BigDecimal("12000.00"));

    private final ReservationGenerations generations = mock(ReservationGenerations.class);
    private final CustomerCreditService credit = mock(CustomerCreditService.class);
    private final PostgresAdvisoryLock lock = mock(PostgresAdvisoryLock.class);
    private final CreditReservationSweep sweep = new CreditReservationSweep(generations, credit, lock,
        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), 50);

    /**
     * Customer CRC decision 2026-10-10: a release whose reference matches no
     * reservation is always 422 RESERVATION_NOT_FOUND and never touches
     * untracked credit, so a reserve that was sent but never answered is
     * released by its reference once the grace period has passed (no blind
     * skip, no UNCONFIRMED for an operator). The sweep records the intent
     * (compare-and-set on the row's age) before the release is sent.
     */
    @Test
    @SuppressWarnings("unchecked")
    void releasesRecordedAndUnansweredReservationsByReferenceAndCarriesOnAfterAFailure() {
        Instant before = NOW.minus(Duration.ofMinutes(10));
        when(lock.runExclusively(any())).thenAnswer(invocation -> Optional.ofNullable(((Supplier<Object>) invocation.getArgument(0)).get()));
        when(generations.unresolved(before, 50)).thenReturn(List.of(
            row("LOAN-SWEEP-PENDING", 1, null, 0),
            row("LOAN-SWEEP-HELD", 0, State.RESERVED, null),
            row("LOAN-SWEEP-DOWN", 0, State.RESERVED, null),
            row("LOAN-SWEEP-UNKNOWN", 0, State.RESERVING, null),
            row("LOAN-SWEEP-APPLIED", 2, State.RESERVING, null),
            row("LOAN-SWEEP-LEGACY", 0, State.UNCONFIRMED, null),
            row("LOAN-SWEEP-EXCEEDS", 1, State.RESERVING, null),
            row("LOAN-SWEEP-RACED", 0, State.RESERVING, null)));
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-PENDING")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-HELD")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-DOWN")), any(), any()))
            .thenThrow(new CustomerCreditUnavailableException("down"));
        when(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-UNKNOWN"), 0, before)).thenReturn(true);
        when(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-APPLIED"), 2, before)).thenReturn(true);
        when(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-LEGACY"), 0, before)).thenReturn(true);
        when(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-EXCEEDS"), 1, before)).thenReturn(true);
        // a disbursement retry re-sent the reserve meanwhile (the row is no longer stale): nothing is sent
        when(generations.beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-RACED"), 0, before)).thenReturn(false);
        // 422 RESERVATION_NOT_FOUND: the reserve was never applied
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-UNKNOWN")), any(), any())).thenReturn(UnusedReservation.NONE);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-APPLIED")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-LEGACY")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-EXCEEDS")), any(), any()))
            .thenThrow(new CreditReservationNeedsOperatorException("RELEASE_EXCEEDS_RESERVATION", "refused"));

        CreditReservationSweep.Result result = sweep.sweepOnce();

        assertThat(result).isEqualTo(new CreditReservationSweep.Result(true, 4, 1, 1, 1));
        verify(credit).releaseUnusedReservation(LoanId.of("LOAN-SWEEP-HELD"), CUSTOMER, PRINCIPAL);
        verify(credit).releaseUnusedReservation(LoanId.of("LOAN-SWEEP-UNKNOWN"), CUSTOMER, PRINCIPAL);
        verify(credit, never()).releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-RACED")), any(), any());
        // recorded before it is sent
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(generations, credit);
        order.verify(generations).beginCompensationOfUnanswered(LoanId.of("LOAN-SWEEP-UNKNOWN"), 0, before);
        order.verify(credit).releaseUnusedReservation(LoanId.of("LOAN-SWEEP-UNKNOWN"), CUSTOMER, PRINCIPAL);
        // a reservation that was accepted, or has a pending release, needs no new intent
        verify(generations, never()).beginCompensationOfUnanswered(eq(LoanId.of("LOAN-SWEEP-HELD")), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(generations, never()).beginCompensationOfUnanswered(eq(LoanId.of("LOAN-SWEEP-PENDING")), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void doesNothingWhileAnotherReplicaSweeps() {
        when(lock.runExclusively(any())).thenReturn(Optional.empty());

        assertThat(sweep.sweepOnce().ran()).isFalse();
        verify(generations, never()).unresolved(any(), eq(50));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLockIsASessionAdvisoryLockReleasedAfterTheWork() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
            ((ConnectionCallback<Object>) invocation.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true, true, false, false);
        PostgresAdvisoryLock advisory = new PostgresAdvisoryLock(jdbc, CreditReservationSweep.SWEEP_LOCK_KEY);

        assertThat(advisory.runExclusively(() -> "swept")).contains("swept");
        assertThat(advisory.runExclusively(() -> "not run")).isEmpty();

        verify(connection, org.mockito.Mockito.times(2)).prepareStatement("select pg_try_advisory_lock(?)");
        verify(connection).prepareStatement("select pg_advisory_unlock(?)");
        verify(statement, org.mockito.Mockito.times(3)).setLong(1, CreditReservationSweep.SWEEP_LOCK_KEY);
    }

    private static Unresolved row(String loanId, int generation, State state, Integer pending) {
        return new Unresolved(new Reservation(LoanId.of(loanId), generation, state, pending, NOW.minus(Duration.ofHours(1))),
            CUSTOMER, PRINCIPAL);
    }
}
