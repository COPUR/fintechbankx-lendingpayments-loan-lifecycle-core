package com.bank.loan.domain.port.in;

import com.bank.loan.domain.Loan;

/** FR-005: loan application. */
public interface LoanApplicationUseCase {

    /** Checks the customer's credit and creates the loan with its schedule. */
    Loan applyForLoan(ApplyForLoanCommand command);
}
