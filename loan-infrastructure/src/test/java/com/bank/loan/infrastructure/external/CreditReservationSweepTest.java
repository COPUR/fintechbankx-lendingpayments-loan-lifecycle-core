package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditService.UnusedReservation;
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

    @Test
    @SuppressWarnings("unchecked")
    void releasesRecordedReservationsMarksUnansweredOnesAndCarriesOnAfterAFailure() {
        when(lock.runExclusively(any())).thenAnswer(invocation -> Optional.ofNullable(((Supplier<Object>) invocation.getArgument(0)).get()));
        when(generations.unresolved(NOW.minus(Duration.ofMinutes(10)), 50)).thenReturn(List.of(
            row("LOAN-SWEEP-PENDING", 1, null, 0),
            row("LOAN-SWEEP-HELD", 0, State.RESERVED, null),
            row("LOAN-SWEEP-DOWN", 0, State.RESERVED, null),
            row("LOAN-SWEEP-UNKNOWN", 0, State.RESERVING, null),
            row("LOAN-SWEEP-RACED", 0, State.RESERVING, null)));
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-PENDING")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-HELD")), any(), any())).thenReturn(UnusedReservation.RELEASED);
        when(credit.releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-DOWN")), any(), any()))
            .thenThrow(new CustomerCreditUnavailableException("down"));
        when(generations.markUnconfirmed(LoanId.of("LOAN-SWEEP-UNKNOWN"), 0)).thenReturn(true);

        CreditReservationSweep.Result result = sweep.sweepOnce();

        assertThat(result).isEqualTo(new CreditReservationSweep.Result(true, 2, 1, 1));
        verify(credit).releaseUnusedReservation(LoanId.of("LOAN-SWEEP-HELD"), CUSTOMER, PRINCIPAL);
        verify(credit, never()).releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-UNKNOWN")), any(), any());
        verify(credit, never()).releaseUnusedReservation(eq(LoanId.of("LOAN-SWEEP-RACED")), any(), any());
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
