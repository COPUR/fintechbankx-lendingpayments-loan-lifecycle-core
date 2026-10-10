package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CreditReservationNeedsOperatorException;
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
 * REJECTED, ...) whose row has not changed for the grace period, and
 * releases what they may still hold, always under the reservation's
 * compensation key ({@code <reserve key>:compensation}) with the loan id as
 * reference, the reference the reserve carried
 * ({@link CustomerCreditService#releaseUnusedReservation}):
 * <ul>
 *   <li>a pending compensation is re-sent;</li>
 *   <li>a reservation recorded as RESERVED is released. On a still APPROVED
 *       loan this releases an abandoned reservation; a disbursement that
 *       commits later rolls back because its reservation is gone;</li>
 *   <li>a reserve that was sent but never answered (RESERVING, or
 *       UNCONFIRMED from an earlier release) is released by its reference
 *       too, no longer skipped: the intent is recorded first
 *       ({@link ReservationGenerations#beginCompensationOfUnanswered}, which
 *       loses to a disbursement retry that re-sent the reserve meanwhile),
 *       then the release is sent.</li>
 * </ul>
 * Outcomes: accepted means released; 422 RESERVATION_NOT_FOUND means nothing
 * was reserved under the reference, the row is cleared and the release
 * counted (loan_credit_releases_unmatched_total); 422
 * RELEASE_EXCEEDS_RESERVATION leaves the row for an operator
 * (loan_credit_reservations_operator{reason="release_exceeds_reservation"});
 * anything else keeps the release pending for the next run.
 *
 * <p>Releasing an unanswered reserve is safe only because the customer
 * service answers a release whose reference matches no reservation with 422
 * RESERVATION_NOT_FOUND and never takes it from untracked or migrated credit
 * (customer CRC decision 2026-10-10; customer-profile-kyc-core PR #13 commit
 * a6ebe01, not merged yet). It must not run against a customer service
 * without that change. It is also safe only if no reserve is still in flight
 * after the grace period, which the configuration enforces (the grace must
 * outlast max-attempts x (connect + read timeout)).
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

    /**
     * What one run did; {@code ran} is false when another replica held the
     * lock. {@code nothingHeld}: releases that found nothing to release
     * (RESERVATION_NOT_FOUND); {@code heldForOperator}: releases refused with
     * RELEASE_EXCEEDS_RESERVATION, or rows already waiting for an operator.
     */
    public record Result(boolean ran, int released, int nothingHeld, int heldForOperator, int failed) {
        static final Result SKIPPED = new Result(false, 0, 0, 0, 0);
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
        int nothingHeld = 0;
        int heldForOperator = 0;
        int failed = 0;
        for (ReservationGenerations.Unresolved row : generations.unresolved(before, batchSize)) {
            ReservationGenerations.Reservation reservation = row.reservation();
            String loanId = reservation.loanId().getValue();
            try {
                if (reservation.pendingCompensation() == null && isUnanswered(reservation.state())) {
                    if (!generations.beginCompensationOfUnanswered(reservation.loanId(), reservation.generation(), before)) {
                        log.info("Reserve of loan {} (generation {}) moved on meanwhile; not released this run",
                            loanId, reservation.generation());
                        continue;
                    }
                    log.warn("Reserve of loan {} (generation {}) has had no answer since {}; releasing it by its reference",
                        loanId, reservation.generation(), reservation.updatedAt());
                }
                UnusedReservation outcome = customerCredit.releaseUnusedReservation(
                    reservation.loanId(), row.customerId(), row.principal());
                if (outcome == UnusedReservation.RELEASED) {
                    released++;
                    log.info("Recovery sweep released the unused credit reservation of loan {}", loanId);
                } else {
                    nothingHeld++;
                }
            } catch (CreditReservationNeedsOperatorException held) {
                heldForOperator++;
                log.error("Recovery sweep: the release for loan {} was refused with {}; left for an operator",
                    loanId, held.getReason());
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Recovery sweep could not release the credit reservation of loan {}; retried next run",
                    loanId, failure);
            }
        }
        return new Result(true, released, nothingHeld, heldForOperator, failed);
    }

    private static boolean isUnanswered(ReservationGenerations.State state) {
        return state == ReservationGenerations.State.RESERVING || state == ReservationGenerations.State.UNCONFIRMED;
    }
}
