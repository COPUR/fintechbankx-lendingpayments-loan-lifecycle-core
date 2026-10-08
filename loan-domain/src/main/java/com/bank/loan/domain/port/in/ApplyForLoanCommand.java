package com.bank.loan.domain.port.in;

import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.LoanTerm;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/** A customer applies for a loan (nominal annual rate, amortised schedule). */
public record ApplyForLoanCommand(CustomerId customerId, Money principal, InterestRate annualRate, LoanTerm term) {

    public ApplyForLoanCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(term, "term");
    }
}
