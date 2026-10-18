package com.bank.loan.domain;

import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/**
 * The part of one repayment applied to one installment, split into the
 * interest and principal it settled (interest first). amount = principal + interest.
 */
public record InstallmentAllocation(int installmentNumber, Money amount, Money principal, Money interest) {

    public InstallmentAllocation {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(interest, "interest");
        if (installmentNumber <= 0) {
            throw new IllegalArgumentException("Installment number must be positive");
        }
        if (!amount.equals(principal.add(interest))) {
            throw new IllegalArgumentException("Allocation amount must equal principal plus interest");
        }
    }
}
