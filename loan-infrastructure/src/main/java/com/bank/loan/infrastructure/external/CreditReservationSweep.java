package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditService.UnusedReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Review 5460235552: recovers credit reservations that nothing else would,
 * on start-up and then at a fixed delay, so recovery does not wait for
 * someone to retry the disbursement. One replica sweeps at a time
 * ({@link PostgresAdvisoryLock}).
 *
 * <p>It looks at loans that were never disbursed (APPROVED, CANCELLED,
 * REJECTED, ...) whose row has not changed for the grace period:
 * <ul>
 *   <li>a pending compensation is re-sent under its compensation key, and a
 *       reservation recorded as RESERVED is released under its compensation
 *       key ({@link CustomerCreditService#releaseUnusedReservation}, the same
 *       idempotent keys the request path uses). On a still APPROVED loan this
 *       releases an abandoned reservation; a disbursement that commits later
 *       rolls back because its reservation is gone;</li>
 *   <li>a reservation still RESERVING (sent, never answered) is not released:
 *       the customer service's release does not check that a reservation
 *       exists (customer #13 CreditProfile.releaseCredit), so a release of a
 *       reserve that was never applied would free other loans' credit. It is
 *       marked UNCONFIRMED for an operator
 *       (loan_credit_reservations_operator{reason="unconfirmed"}).</li>
 * </ul>
 */
public class CreditReservationSweep {

    /** "ln_crd" */
    public static final long SWEEP_LOCK_KEY = 0x6C6E5F637264L;
    private static final Logger log = LoggerFactory.getLogger(CreditReservationSweep.class);

    private final ReservationGenerations generations;
    private final CustomerCreditService customerCredit;
    private final PostgresAdvisoryLock lock;
    private final Clock clock;
    private final Duration grace;
    private final int batchSize;

    /** What one run did; {@code ran} is false when another replica held the lock. */
    public record Result(boolean ran, int released, int unconfirmed, int failed) {
        static final Result SKIPPED = new Result(false, 0, 0, 0);
    }

    public CreditReservationSweep(ReservationGenerations generations, CustomerCreditService customerCredit,
                                  PostgresAdvisoryLock lock, Clock clock, Duration grace, int batchSize) {
        this.generations = generations;
        this.customerCredit = customerCredit;
        this.lock = lock;
        this.clock = clock;
        this.grace = grace;
        this.batchSize = batchSize;
    }

    public Result sweepOnce() {
        return lock.runExclusively(this::sweep).orElse(Result.SKIPPED);
    }

    private Result sweep() {
        Instant before = clock.instant().minus(grace);
        int released = 0;
        int unconfirmed = 0;
        int failed = 0;
        for (ReservationGenerations.Unresolved row : generations.unresolved(before, batchSize)) {
            ReservationGenerations.Reservation reservation = row.reservation();
            String loanId = reservation.loanId().getValue();
            try {
                if (reservation.pendingCompensation() == null
                        && reservation.state() == ReservationGenerations.State.RESERVING) {
                    if (generations.markUnconfirmed(reservation.loanId(), reservation.generation())) {
                        unconfirmed++;
                        log.error("Reserve of loan {} (generation {}) has had no answer since {}; not released blind, "
                            + "marked UNCONFIRMED for an operator", loanId, reservation.generation(), reservation.updatedAt());
                    }
                    continue;
                }
                UnusedReservation outcome = customerCredit.releaseUnusedReservation(
                    reservation.loanId(), row.customerId(), row.principal());
                if (outcome == UnusedReservation.RELEASED) {
                    released++;
                    log.info("Recovery sweep released the unused credit reservation of loan {}", loanId);
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Recovery sweep could not release the credit reservation of loan {}; retried next run",
                    loanId, failure);
            }
        }
        return new Result(true, released, unconfirmed, failed);
    }
}
