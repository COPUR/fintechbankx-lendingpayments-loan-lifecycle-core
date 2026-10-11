package com.bank.loan.domain.port.in;

import com.bank.loan.domain.Loan;

/** FR-008: repayments, from this service's API or from the payment context. */
public interface LoanRepaymentUseCase {

    /** Applies a repayment; idempotent per (requestScope, idempotencyKey) when a key is given. */
    Loan repay(RepayLoanCommand command);

    /**
     * Applies a repayment the payment context completed. Idempotent on the payment id.
     *
     * @return true if applied now, false if it had been applied before
     */
    boolean recordCompletedLoanPayment(RecordCompletedLoanPaymentCommand command);
}
