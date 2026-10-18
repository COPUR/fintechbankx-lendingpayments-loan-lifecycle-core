package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.RepaymentBooking;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit level: which values each statement binds. The SQL runs against
 * PostgreSQL in LoanLifecycleServiceIT, where repayments write their bookings.
 */
class JdbcRepaymentBookingsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcRepaymentBookings bookings = new JdbcRepaymentBookings(jdbc);

    @Test
    void recordBindsTheAmountsAndAccountsOfTheBooking() {
        bookings.record(new RepaymentBooking("loan-repayment:PAY-1", LoanId.of("LOAN-1"),
            Money.aed(new BigDecimal("1000.00")), Money.aed(new BigDecimal("200.00")),
            "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", "INTEREST-INCOME"));

        verify(jdbc).update(startsWith("INSERT INTO repayment_booking"), eq("loan-repayment:PAY-1"), eq("LOAN-1"),
            eq("AED"), eq(new BigDecimal("1000.00")), eq(new BigDecimal("200.00")), eq("LOAN-RECEIVABLE"),
            eq("DISBURSEMENT-CLEARING"), eq("INTEREST-INCOME"));
    }

    @Test
    void markBookedKeepsTheJournalEntryAndTouchesOnlyUnbookedRows() {
        Instant bookedAt = Instant.parse("2026-10-08T06:00:00Z");

        bookings.markBooked("loan-repayment:PAY-1", "JE-1", bookedAt);

        verify(jdbc).update(startsWith("UPDATE repayment_booking SET booked_at"), eq(Timestamp.from(bookedAt)),
            eq("JE-1"), eq("loan-repayment:PAY-1"));
    }

    @Test
    void anAttemptKeepsOnlyTheCodeAndAParkedBookingIsMarkedParked() {
        bookings.recordAttempt("loan-repayment:PAY-2", "ACCOUNTS_LEDGER_UNAVAILABLE");
        verify(jdbc).update(startsWith("UPDATE repayment_booking SET attempts = attempts + 1, last_error"),
            eq("ACCOUNTS_LEDGER_UNAVAILABLE"), eq("loan-repayment:PAY-2"));

        bookings.park("loan-repayment:PAY-2", "ACCOUNT_NOT_OPERATIONAL");
        verify(jdbc).update(startsWith("UPDATE repayment_booking SET parked = TRUE"),
            eq("ACCOUNT_NOT_OPERATIONAL"), eq("loan-repayment:PAY-2"));
    }

    @Test
    void aRowThatCannotBeReadIsParkedAndTheReadableRowsAreReturned() throws Exception {
        ResultSet good = row("loan-repayment:PAY-3", "AED", "100.0000");
        ResultSet bad = row("loan-repayment:PAY-4", "AED", "100.0050");
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            handler.processRow(bad);
            handler.processRow(good);
            return null;
        }).when(jdbc).query(startsWith("SELECT reference"), any(RowCallbackHandler.class), eq(2));

        List<RepaymentBooking> pending = bookings.pending(2);

        assertThat(pending).extracting(RepaymentBooking::reference).containsExactly("loan-repayment:PAY-3");
        verify(jdbc).update(startsWith("UPDATE repayment_booking SET parked = TRUE"),
            eq(JdbcRepaymentBookings.UNREADABLE), eq("loan-repayment:PAY-4"));
    }

    @Test
    void theBacklogReadsTheParkedCountAndTheOldestPendingBooking() {
        Instant recorded = Instant.parse("2026-10-08T06:00:00Z");
        when(jdbc.queryForObject(startsWith("SELECT count(*)"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(startsWith("SELECT min(created_at)"), eq(Timestamp.class)))
            .thenReturn(Timestamp.from(recorded)).thenReturn(null);

        assertThat(bookings.countParked()).isEqualTo(3);
        assertThat(bookings.oldestPending()).contains(recorded);
        assertThat(bookings.oldestPending()).isEmpty();
    }

    private static ResultSet row(String reference, String currency, String principal) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("reference")).thenReturn(reference);
        when(rs.getString("loan_id")).thenReturn("LOAN-1");
        when(rs.getString("currency")).thenReturn(currency);
        when(rs.getBigDecimal("principal_amount")).thenReturn(new BigDecimal(principal));
        when(rs.getBigDecimal("interest_amount")).thenReturn(new BigDecimal("0.0000"));
        when(rs.getString("receivable_account_id")).thenReturn("LOAN-RECEIVABLE");
        when(rs.getString("clearing_account_id")).thenReturn("DISBURSEMENT-CLEARING");
        return rs;
    }
}
