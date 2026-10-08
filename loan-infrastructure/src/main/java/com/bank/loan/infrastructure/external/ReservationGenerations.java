package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;

/**
 * Counts how often a loan's credit reservation was compensated (released
 * because the disbursement transaction rolled back). The reservation's
 * idempotency key carries the generation, so a later disbursement attempt
 * makes a fresh reservation instead of replaying the compensated one.
 */
public interface ReservationGenerations {

    int current(LoanId loanId);

    /** Records a compensation; runs in its own transaction. */
    void advance(LoanId loanId);
}
