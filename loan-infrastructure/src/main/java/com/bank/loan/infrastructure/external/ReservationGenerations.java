package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;

import java.util.OptionalInt;

/**
 * Counts how often a loan's credit reservation was compensated (released
 * because the disbursement transaction rolled back) and remembers a
 * compensation that was started but not confirmed. The reservation's
 * idempotency key carries the generation, so a later disbursement attempt
 * makes a fresh reservation instead of replaying the compensated one.
 *
 * Order (review 5455936515): the intent is stored first, in its own
 * transaction, and only then is the release sent. A stored intent whose
 * release was not confirmed is re-sent under the same key before the next
 * reservation.
 */
public interface ReservationGenerations {

    int current(LoanId loanId);

    /**
     * In its own transaction: the loan moves to generation {@code generation + 1}
     * and the compensation of {@code generation} is recorded as pending.
     * Throws if that cannot be stored; then no release may be sent.
     */
    void beginCompensation(LoanId loanId, int generation);

    /** The generation whose compensation was started but not confirmed, if any. */
    OptionalInt pendingCompensation(LoanId loanId);

    /** In its own transaction: the release of {@code generation} was accepted. */
    void compensationDone(LoanId loanId, int generation);
}
