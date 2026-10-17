package com.bank.loan.domain.port.in;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;

/** Reads a loan (FR-005). */
public interface LoanQueryUseCase {

    Loan findLoan(LoanId loanId);
}
