package com.bank.loan.domain.port.out;

import com.bank.loan.domain.LoanId;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

/**
 * Out-port to the customer context, which owns credit limits and
 * reservations (svc-cus-profile-kyc). Implemented by an anti-corruption
 * adapter in loan-infrastructure.
 *
 * Movements are keyed by the loan: the adapter derives the provider's
 * idempotency key from the loan id ({@code {loanId}:reserve},
 * {@code {loanId}:release}), so a retried command never moves credit twice.
 *
 * Callers must not hold a database transaction open while calling this port:
 * every method is a remote call, except {@link #markReservationUsed}, which is
 * local bookkeeping and belongs inside the disbursement transaction.
 *
 * <ul>
 *   <li>{@link CreditDecision#REFUSED}: the customer service answered "not
 *       enough credit" (422 INSUFFICIENT_CREDIT).</li>
 *   <li>{@link CreditCurrencyMismatchException}: the amount is in a currency
 *       the customer's credit is not held in.</li>
 *   <li>{@link CreditCustomerNotFoundException}: the customer service does
 *       not know the customer.</li>
 *   <li>{@link CreditReservationNeedsOperatorException}: the loan's
 *       reservation is held for an operator after a refused compensating
 *       release; not retryable until the operator has resolved it.</li>
 *   <li>{@link CustomerCreditUnavailableException}: no usable answer (down,
 *       timeout, no service token, kept reporting concurrent updates, or
 *       rejected the request).</li>
 * </ul>
 */
public interface CustomerCreditService {

    /** What became of the reservation of a loan that will not be disbursed. */
    enum UnusedReservation {
        /** No reservation is held for the loan: none was made, or it was released already. */
        NONE,
        /** The held reservation was released under its compensation key. */
        RELEASED,
        /**
         * A reservation was requested, but whether the customer service applied
         * it is not known (its reserve may still be in flight). Nothing is
         * released now; the infrastructure's recovery sweep releases it by its
         * reference once that can no longer be the case.
         */
        UNCONFIRMED
    }

    /** Outcome of a credit movement the customer service answered. */
    enum CreditDecision {
        ACCEPTED,
        /** not enough available credit */
        REFUSED
    }

    /**
     * True if the customer's available credit covers the amount.
     *
     * @throws CreditCurrencyMismatchException if the credit is held in another currency
     */
    boolean hasAvailableCredit(CustomerId customerId, Money amount);

    /** Reserves credit for a loan being disbursed. */
    CreditDecision reserveCredit(LoanId loanId, CustomerId customerId, Money amount);

    /**
     * Undoes a reservation whose disbursement could not be stored, so the
     * next attempt reserves afresh instead of replaying the undone one.
     */
    CreditDecision cancelReservation(LoanId loanId, CustomerId customerId, Money amount);

    /**
     * Releases the reservation of a loan that was cancelled or rejected (or
     * abandoned) before a disbursement committed, but only if this service
     * recorded that the customer service accepted it. A second call sends
     * nothing.
     *
     * @throws CustomerCreditUnavailableException if the release could not be
     *         confirmed; it stays recorded as pending and is re-sent under the
     *         same key later
     */
    UnusedReservation releaseUnusedReservation(LoanId loanId, CustomerId customerId, Money amount);

    /**
     * Inside the disbursement transaction: the disbursement uses the loan's
     * reservation. No remote call.
     *
     * @throws CustomerCreditUnavailableException if the reservation was
     *         released meanwhile (cancellation or the recovery sweep), so the
     *         disbursement must roll back
     */
    void markReservationUsed(LoanId loanId);

    /** Releases the loan's reserved credit once the loan is repaid. */
    CreditDecision releaseCredit(LoanId loanId, CustomerId customerId, Money amount);

    /** The customer's available credit as reported (in the currency the customer service holds it in). */
    Money getAvailableCredit(CustomerId customerId);
}
