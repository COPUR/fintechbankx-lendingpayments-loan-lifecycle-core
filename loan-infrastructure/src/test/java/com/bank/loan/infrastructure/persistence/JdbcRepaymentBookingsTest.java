package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.RepaymentBooking;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
