package com.bank.loan.domain.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Out-port for the repayment bookings. A booking is recorded in the same
 * transaction as its repayment; the booking sweep reads the bookings that are
 * not yet in the ledger, books them and marks them. A booking the ledger
 * refused as sent is parked for an operator and is not read again until
 * cleared (runbook section 10).
 */
public interface RepaymentBookings {

    /** Stores the booking in the caller's transaction, so it commits or rolls back with the repayment. */
    void record(RepaymentBooking booking);

    /** Bookings not yet in the ledger and not parked, oldest first, at most {@code limit}. */
    List<RepaymentBooking> pending(int limit);

    /** The ledger booked the entry (or replayed it): the booking is done. */
    void markBooked(String reference, String journalEntryId, Instant bookedAt);

    /** The ledger could not answer: the booking stays pending and the code is kept. Never the ledger's text. */
    void recordAttempt(String reference, String code);

    /** The ledger refused the entry as sent: the booking is parked until an operator clears it. */
    void park(String reference, String code);
}
