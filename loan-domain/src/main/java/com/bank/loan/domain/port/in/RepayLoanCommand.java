package com.bank.loan.domain.port.in;

import com.bank.loan.domain.LoanId;
import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/**
 * A repayment sent to this service's API.
 *
 * @param requestScope   the caller (customer id for customers, principal otherwise); scope of the key
 * @param idempotencyKey the caller's x-idempotency-key, or null
 */
public record RepayLoanCommand(LoanId loanId, Money amount, String requestScope, String idempotencyKey) {

    public RepayLoanCommand {
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(amount, "amount");
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            idempotencyKey = null;
        }
        if (idempotencyKey != null && requestScope == null) {
            throw new IllegalArgumentException("An idempotency key needs the caller's scope");
        }
        if (idempotencyKey == null) {
            requestScope = null;
        }
    }

    public static RepayLoanCommand withoutKey(LoanId loanId, Money amount) {
        return new RepayLoanCommand(loanId, amount, null, null);
    }

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null;
    }
}
