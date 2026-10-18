package com.bank.loan.domain.port.out;

import com.bank.loan.domain.LoanId;

/**
 * Raised when a disbursement's booking may exist in the accounts ledger while
 * the loan is not disbursed: the ledger did not answer, the disbursement
 * could not be stored after it was booked, or the ledger already holds an
 * entry under the reference with other lines. A retry replays the booking under
 * the same reference, so the signal starts a reconciliation, it does not
 * change the answer to the caller. It carries the loan id and the reference
 * only, never accounts or amounts (runbook section 10).
 */
public interface UnresolvedBookingSignal {

    void raise(LoanId loanId, String reference, Cause cause);

    enum Cause {
        /** The ledger gave no usable answer (timeout, 5xx, unreadable answer): the entry may have been booked. */
        LEDGER_UNANSWERED,
        /** The entry was booked, then the loan could not be stored as disbursed. */
        NOT_STORED,
        /** The ledger already holds an entry under this reference with other lines (409 IDEMPOTENCY_KEY_REUSED). */
        REFERENCE_REUSED
    }
}
