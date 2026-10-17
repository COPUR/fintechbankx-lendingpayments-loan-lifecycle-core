package com.bank.loan.domain.port.in;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;

/** FR-007: disburse an approved loan, reserving the customer's credit first. */
public interface LoanDisbursementUseCase {

    Loan disburse(LoanId loanId);
}
