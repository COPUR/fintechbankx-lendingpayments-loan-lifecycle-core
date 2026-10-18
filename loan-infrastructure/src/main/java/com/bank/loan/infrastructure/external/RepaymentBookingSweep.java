package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import com.bank.loan.domain.port.out.RepaymentBooking;
import com.bank.loan.domain.port.out.RepaymentBookings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Books the applied repayments that are not in the ledger yet
 * ({@link RepaymentBooking}). The repayment is already applied when its booking
 * is written, so nothing waits on the ledger here: a booking the ledger cannot
 * answer stays pending and is posted again by a later run, under the same
 * reference, so the ledger replays it rather than booking twice. A booking the
 * ledger refuses as sent is parked for an operator (runbook section 10), and so
 * is a booking that fails here for any other reason (building its entry, or
 * marking it booked), so one bad row cannot hold up every later booking. A
 * parked booking that was in fact booked is replayed by the ledger when the
 * operator clears it.
 *
 * One replica sweeps at a time ({@link PostgresAdvisoryLock}). Each run posts
 * one batch, in order, and stops at the first answer that the ledger is
 * unavailable: the rest waits for the next run. No database transaction is open
 * while the ledger is called.
 */
public class RepaymentBookingSweep {

    /** "ln_rpb": this sweep's lock, distinct from the reservation sweep and the outbox relay. */
    public static final long SWEEP_LOCK_KEY = 0x6C6E5F727062L;

    static final String LEDGER_UNAVAILABLE = "ACCOUNTS_LEDGER_UNAVAILABLE";

    static final String SWEEP_FAILED = "SWEEP_FAILED";

    private static final Logger log = LoggerFactory.getLogger(RepaymentBookingSweep.class);

    private final RepaymentBookings bookings;
    private final JournalEntryPort journal;
    private final PostgresAdvisoryLock lock;
    private final int batchSize;

    /** One run: {@code ran} is false when another replica held the lock. */
    public record Result(boolean ran, int booked, int failed, int parked) {

        static Result skipped() {
            return new Result(false, 0, 0, 0);
        }
    }

    public RepaymentBookingSweep(RepaymentBookings bookings, JournalEntryPort journal,
                                 PostgresAdvisoryLock lock, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("loan.repayment-booking.sweep.batch-size must be positive");
        }
        this.bookings = bookings;
        this.journal = journal;
        this.lock = lock;
        this.batchSize = batchSize;
    }

    public Result sweepOnce() {
        return lock.runExclusively(this::bookPending).orElseGet(Result::skipped);
    }

    /** One batch, in order. Package-visible so the tests run it without a database lock. */
    Result bookPending() {
        List<RepaymentBooking> batch = bookings.pending(batchSize);
        int booked = 0;
        int failed = 0;
        int parked = 0;
        for (RepaymentBooking booking : batch) {
            try {
                JournalEntryReceipt receipt = journal.post(booking.toJournalEntry());
                bookings.markBooked(booking.reference(), receipt.journalEntryId(), receipt.bookedAt());
                booked++;
            } catch (JournalEntryRejectedException refused) {
                bookings.park(booking.reference(), refused.getCode());
                parked++;
                log.error("Repayment booking {} was refused by the accounts ledger ({}); parked for an operator",
                    booking.reference(), refused.getCode());
            } catch (AccountsLedgerUnavailableException unavailable) {
                bookings.recordAttempt(booking.reference(), LEDGER_UNAVAILABLE);
                failed++;
                log.warn("Repayment booking {} not booked: the accounts ledger did not answer; retried next run",
                    booking.reference());
                break;
            } catch (RuntimeException failure) {
                bookings.park(booking.reference(), SWEEP_FAILED);
                parked++;
                log.error("Repayment booking {} failed in the sweep ({}); parked for an operator",
                    booking.reference(), failure.getClass().getSimpleName());
            }
        }
        return new Result(true, booked, failed, parked);
    }
}
