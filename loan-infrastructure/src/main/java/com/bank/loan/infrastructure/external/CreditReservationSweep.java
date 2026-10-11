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
 *       UNCONFIRMED from an earlier release) follows
 *       {@code loan.credit-reservation.sweep.release-unanswered}
 *       (CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED):
 *       <ul>
 *         <li>off (the default): never released blind. A RESERVING row is
 *             marked UNCONFIRMED ({@link ReservationGenerations#markUnconfirmed})
 *             and left for an operator
 *             (loan_credit_reservations_operator{reason="unconfirmed"}, runbook
 *             section 8); UNCONFIRMED rows are not read at all. The behaviour
 *             before the customer's release by reference;</li>
 *         <li>on: released by its reference. The intent is recorded first
 *             ({@link ReservationGenerations#beginCompensationOfUnanswered},
 *             which loses to a disbursement retry that re-sent the reserve
 *             meanwhile), then the release is sent.</li>
 *       </ul></li>
 * </ul>
 * Outcomes of a release: accepted means released; 422 RESERVATION_NOT_FOUND
 * means nothing is held under the reference (never reserved, or the
 * reservation already holds 0), the row is cleared and the release counted
 * (loan_credit_releases_unmatched_total); 422 RELEASE_EXCEEDS_RESERVATION
 * leaves the row for an operator
 * (loan_credit_reservations_operator{reason="release_exceeds_reservation"});
 * anything else keeps the release pending for the next run.
 *
 * <p>Releasing an unanswered reserve is safe only because the customer
 * service answers a release whose reference matches no reservation with 422
 * RESERVATION_NOT_FOUND and never takes it from untracked or migrated credit
 * (customer CRC decision 2026-10-10; customer-profile-kyc-core PR #13 commit
 * a6ebe01, not merged yet). release-unanswered must stay off against a
 * customer service without that change. It is also safe only if no reserve
 * is still in flight after the grace period, which the configuration
 * enforces (the grace must outlast max-attempts x (connect + read timeout)).
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
    private final boolean releaseUnanswered;

    /**
     * What one run did; {@code ran} is false when another replica held the
     * lock. {@code nothingHeld}: releases that found nothing to release
     * (RESERVATION_NOT_FOUND); {@code heldForOperator}: releases refused with
     * RELEASE_EXCEEDS_RESERVATION, and, with release-unanswered off, reserves
     * never answered that were marked UNCONFIRMED or already waited for an
     * operator.
     */
    public record Result(boolean ran, int released, int nothingHeld, int heldForOperator, int failed) {
        static final Result SKIPPED = new Result(false, 0, 0, 0, 0);
    }

    public CreditReservationSweep(ReservationGenerations generations, CustomerCreditService customerCredit,
                                  PostgresAdvisoryLock lock, Clock clock, Duration grace, int batchSize,
                                  boolean releaseUnanswered) {
        this.generations = generations;
        this.customerCredit = customerCredit;
        this.lock = lock;
        this.clock = clock;
        this.grace = grace;
        this.batchSize = batchSize;
        this.releaseUnanswered = releaseUnanswered;
    }

    /** True when unanswered reserves are released by reference (CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED). */
    public boolean releasesUnanswered() {
        return releaseUnanswered;
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
        for (ReservationGenerations.Unresolved row : generations.unresolved(before, batchSize, releaseUnanswered)) {
            ReservationGenerations.Reservation reservation = row.reservation();
            String loanId = reservation.loanId().getValue();
            try {
                if (reservation.pendingCompensation() == null && isUnanswered(reservation.state())) {
                    if (!releaseUnanswered) {
                        if (leaveForOperator(reservation, before)) {
                            heldForOperator++;
                        }
                        continue;
                    }
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

    /**
     * release-unanswered off: a stale RESERVING row becomes UNCONFIRMED for an
     * operator; a row that is UNCONFIRMED already stays so. Neither is
     * released. False when a disbursement retry refreshed the row meanwhile,
     * so it is neither marked nor counted this run.
     */
    private boolean leaveForOperator(ReservationGenerations.Reservation reservation, Instant before) {
        String loanId = reservation.loanId().getValue();
        if (reservation.state() == ReservationGenerations.State.UNCONFIRMED) {
            return true;
        }
        if (!generations.markUnconfirmed(reservation.loanId(), reservation.generation(), before)) {
            log.info("Reserve of loan {} (generation {}) moved on meanwhile; not marked UNCONFIRMED this run",
                loanId, reservation.generation());
            return false;
        }
        log.error("Reserve of loan {} (generation {}) has had no answer since {}; not released blind "
            + "(release-unanswered is off), marked UNCONFIRMED for an operator", loanId, reservation.generation(),
            reservation.updatedAt());
        return true;
    }

    private static boolean isUnanswered(ReservationGenerations.State state) {
        return state == ReservationGenerations.State.RESERVING || state == ReservationGenerations.State.UNCONFIRMED;
    }
}
