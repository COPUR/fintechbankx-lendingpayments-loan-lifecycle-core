package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * This service's record of the credit reservations it asked the customer
 * service for, one row per loan (credit_reservation_generation).
 *
 * <p>The generation counts how often the loan's reservation was compensated
 * (released because no disbursement used it); the reservation's idempotency
 * key carries it, so a later disbursement attempt makes a fresh reservation
 * instead of replaying the compensated one. A compensation's intent is stored
 * first, in its own transaction, and only then is the release sent (review
 * 5455936515); a stored intent whose release was not confirmed is re-sent
 * under the same key.
 *
 * <p>Review 5460235552: the reservation itself is recorded too, before it is
 * sent ({@link State}). On the request path a release is only sent for a
 * reservation known to be accepted. A reserve that was sent but never
 * answered is handled by the recovery sweep only, once the grace period has
 * passed, in one of two ways chosen by
 * {@code loan.credit-reservation.sweep.release-unanswered}
 * (CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED): off (the default), the row is
 * marked UNCONFIRMED for an operator ({@link #markUnconfirmed}) and never
 * released blind; on, it is released by its reference
 * ({@link #beginCompensationOfUnanswered}). The latter relies on the customer
 * service refusing a release whose reference matches no reservation (422
 * RESERVATION_NOT_FOUND, customer CRC decision 2026-10-10,
 * customer-profile-kyc-core PR #13 commit a6ebe01, not merged yet); before
 * it, such a release could free untracked or migrated credit.
 */
public interface ReservationGenerations {

    /** State of the current generation's reservation. */
    enum State {
        /** Recorded before the reserve is sent; whether it was applied is not known. */
        RESERVING,
        /** The customer service accepted the reserve; no disbursement uses it yet. */
        RESERVED,
        /**
         * Was RESERVING past the grace period while release-unanswered is off:
         * an operator must find out whether it was applied (runbook section 8).
         * A retried reserve makes the row RESERVING again; with
         * release-unanswered on, the recovery sweep releases it by reference.
         */
        UNCONFIRMED,
        /** The disbursement that uses the reservation committed. */
        USED
    }

    /** The loan's row; {@code state} null means no reservation is outstanding for {@code generation}. */
    record Reservation(LoanId loanId, int generation, State state, Integer pendingCompensation, Instant updatedAt) {
    }

    /** A row the recovery sweep looks at, with what it needs to re-send a release. */
    record Unresolved(Reservation reservation, CustomerId customerId, Money principal) {
    }

    int current(LoanId loanId);

    Optional<Reservation> find(LoanId loanId);

    /**
     * In its own transaction, before the reserve of {@code generation} is
     * sent: the row is at {@code generation} with state RESERVING. A RESERVING
     * or UNCONFIRMED row of that generation (a retry of an unanswered reserve)
     * becomes RESERVING with a fresh {@code updated_at}, so the recovery sweep
     * does not release it while the retry is in flight; RESERVED is kept.
     * Throws if that cannot be stored, or if the row moved to another
     * generation meanwhile (the sweep took the reserve over); then no reserve
     * may be sent.
     */
    void beginReservation(LoanId loanId, int generation);

    /** In its own transaction: the customer service answered the reserve of {@code generation}. */
    void reservationAnswered(LoanId loanId, int generation, boolean accepted);

    /**
     * In the caller's (disbursement) transaction: the outstanding reservation
     * is USED. False if the loan has a row but no outstanding reservation (it
     * was compensated meanwhile); true without a row (reserved before V9).
     */
    boolean markUsed(LoanId loanId);

    /**
     * In its own transaction, for a caller that knows the reserve of
     * {@code generation} was accepted (the disbursement just failed): the loan
     * moves to {@code generation + 1} and the compensation of
     * {@code generation} is recorded as pending. False, and nothing changes,
     * if that reservation is no longer outstanding (already compensated, or
     * used). Throws if it cannot be stored; then no release may be sent.
     */
    boolean beginCompensation(LoanId loanId, int generation);

    /**
     * Like {@link #beginCompensation}, but only for a reservation recorded as
     * RESERVED: for callers that do not know the answer themselves
     * (cancellation, recovery sweep).
     */
    boolean beginCompensationOfAccepted(LoanId loanId, int generation);

    /**
     * Recovery sweep only, in its own transaction: a reserve of
     * {@code generation} that was sent but never answered (RESERVING, or
     * UNCONFIRMED from an earlier release) and unchanged since
     * {@code before} (the grace period) is taken over for release: the loan
     * moves to {@code generation + 1} and the compensation of
     * {@code generation} is recorded as pending, to be sent by reference
     * under the reserve's compensation key. False, and nothing changes, if
     * the row moved on meanwhile (answered, retried, compensated). Throws if
     * it cannot be stored; then no release may be sent.
     */
    boolean beginCompensationOfUnanswered(LoanId loanId, int generation, Instant before);

    /** The generation whose compensation was started but not confirmed, if any. */
    OptionalInt pendingCompensation(LoanId loanId);

    /** In its own transaction: the release of {@code generation} was accepted. */
    void compensationDone(LoanId loanId, int generation);

    /**
     * Rows of never-disbursed loans with a pending compensation or an
     * outstanding reservation (RESERVING, RESERVED, and UNCONFIRMED only when
     * {@code includeUnconfirmed}: with release-unanswered on, the sweep
     * settles them by reference; off, they belong to an operator), unchanged
     * since {@code before}, oldest first; rows left for an operator after a
     * refused release are not included.
     */
    List<Unresolved> unresolved(Instant before, int limit, boolean includeUnconfirmed);

    /**
     * Recovery sweep with release-unanswered off, in its own transaction: a
     * RESERVING row of {@code generation} unchanged since {@code before} (the
     * grace period) becomes UNCONFIRMED for an operator. False, and nothing
     * changes, if the row moved on meanwhile (answered, retried, compensated).
     */
    boolean markUnconfirmed(LoanId loanId, int generation, Instant before);

    /** Reserves left for an operator as UNCONFIRMED (gauge reason="unconfirmed"). */
    long countUnconfirmed();

    /**
     * In its own transaction: the customer service refused the pending
     * compensation of {@code generation} with {@code code} (a bug signal,
     * RELEASE_EXCEEDS_RESERVATION). It stays pending, is not re-sent, and is
     * left for an operator.
     */
    void releaseRefused(LoanId loanId, int generation, String code);

    /** The code that refused the loan's pending compensation, if an operator must resolve it. */
    Optional<String> releaseRefusedReason(LoanId loanId);

    /** Pending compensations the customer service refused (operator). */
    long countReleaseRefused();

    /** Never-disbursed loans whose credit may still be held: pending compensations and outstanding reservations. */
    long countPending();
}
