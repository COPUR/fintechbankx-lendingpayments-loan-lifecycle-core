package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.JournalEntry;
import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import com.bank.loan.domain.port.out.RepaymentBooking;
import com.bank.loan.domain.port.out.RepaymentBookings;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepaymentBookingSweepTest {

    private static final Instant BOOKED = Instant.parse("2026-10-08T06:00:00Z");

    private final RepaymentBookings bookings = mock(RepaymentBookings.class);
    private final JournalEntryPort journal = mock(JournalEntryPort.class);
    private final PostgresAdvisoryLock lock = mock(PostgresAdvisoryLock.class);
    private final RepaymentBookingSweep sweep = new RepaymentBookingSweep(bookings, journal, lock, 2);

    @Test
    void postsEachPendingBookingUnderItsReferenceAndMarksItBooked() {
        when(bookings.pending(2)).thenReturn(List.of(booking("loan-repayment:PAY-1"), booking("loan-repayment:PAY-2")));
        when(journal.post(any(JournalEntry.class)))
            .thenReturn(new JournalEntryReceipt("JE-1", false, BOOKED))
            .thenReturn(new JournalEntryReceipt("JE-2", true, BOOKED));

        assertThat(sweep.bookPending()).isEqualTo(new RepaymentBookingSweep.Result(true, 2, 0, 0));

        verify(journal).post(org.mockito.ArgumentMatchers.argThat(entry -> entry.reference().equals("loan-repayment:PAY-1")));
        verify(bookings).markBooked("loan-repayment:PAY-1", "JE-1", BOOKED);
        verify(bookings).markBooked("loan-repayment:PAY-2", "JE-2", BOOKED);
    }

    @Test
    void anUnavailableLedgerStopsTheBatchAndLeavesTheRestPending() {
        when(bookings.pending(2)).thenReturn(List.of(booking("loan-repayment:PAY-3"), booking("loan-repayment:PAY-4")));
        when(journal.post(any(JournalEntry.class))).thenThrow(new AccountsLedgerUnavailableException("timed out"));

        assertThat(sweep.bookPending()).isEqualTo(new RepaymentBookingSweep.Result(true, 0, 1, 0));

        verify(journal).post(any(JournalEntry.class));
        verify(bookings).recordAttempt("loan-repayment:PAY-3", "ACCOUNTS_LEDGER_UNAVAILABLE");
        verify(bookings, never()).markBooked(any(), any(), any());
        verify(bookings, never()).park(any(), any());
    }

    @Test
    void aBookingTheLedgerRefusesIsParkedAndTheBatchGoesOn() {
        when(bookings.pending(2)).thenReturn(List.of(booking("loan-repayment:PAY-5"), booking("loan-repayment:PAY-6")));
        when(journal.post(any(JournalEntry.class)))
            .thenThrow(new JournalEntryRejectedException("ACCOUNT_NOT_OPERATIONAL", "account is frozen"))
            .thenReturn(new JournalEntryReceipt("JE-6", false, BOOKED));

        assertThat(sweep.bookPending()).isEqualTo(new RepaymentBookingSweep.Result(true, 1, 0, 1));

        verify(bookings).park("loan-repayment:PAY-5", "ACCOUNT_NOT_OPERATIONAL");
        verify(bookings).markBooked("loan-repayment:PAY-6", "JE-6", BOOKED);
    }

    @Test
    void aBookingThatFailsInTheSweepIsParkedSoItCannotHoldUpTheRest() {
        when(bookings.pending(2)).thenReturn(List.of(booking("loan-repayment:PAY-7"), booking("loan-repayment:PAY-8")));
        when(journal.post(any(JournalEntry.class)))
            .thenReturn(new JournalEntryReceipt("JE-7", false, BOOKED))
            .thenReturn(new JournalEntryReceipt("JE-8", false, BOOKED));
        org.mockito.Mockito.doThrow(new IllegalStateException("column changed"))
            .when(bookings).markBooked("loan-repayment:PAY-7", "JE-7", BOOKED);

        assertThat(sweep.bookPending()).isEqualTo(new RepaymentBookingSweep.Result(true, 1, 0, 1));

        verify(bookings).park("loan-repayment:PAY-7", RepaymentBookingSweep.SWEEP_FAILED);
        verify(bookings).markBooked("loan-repayment:PAY-8", "JE-8", BOOKED);
    }

    @Test
    void nothingPendingMeansNothingPosted() {
        when(bookings.pending(2)).thenReturn(List.of());

        assertThat(sweep.bookPending()).isEqualTo(new RepaymentBookingSweep.Result(true, 0, 0, 0));
        verify(journal, never()).post(any(JournalEntry.class));
    }

    @Test
    void doesNothingWhileAnotherReplicaSweeps() {
        when(lock.runExclusively(any())).thenReturn(Optional.empty());

        assertThat(sweep.sweepOnce()).isEqualTo(new RepaymentBookingSweep.Result(false, 0, 0, 0));
        verify(bookings, never()).pending(anyInt());
    }

    @Test
    void aBatchSizeMustBePositive() {
        assertThatThrownBy(() -> new RepaymentBookingSweep(bookings, journal, lock, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch-size");
    }

    private static RepaymentBooking booking(String reference) {
        return new RepaymentBooking(reference, LoanId.of("LOAN-SWEEP"), Money.aed(new BigDecimal("100.00")),
            Money.aed(new BigDecimal("0.00")), "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null);
    }
}
