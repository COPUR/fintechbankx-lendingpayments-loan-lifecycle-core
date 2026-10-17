package com.bank.loan.domain.port.in;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;

/** FR-006 / FR-008: approve, reject or cancel a loan application. */
public interface LoanDecisionUseCase {

    Loan approve(LoanId loanId);

    Loan reject(LoanId loanId, String reason);

    Loan cancel(LoanId loanId, String reason);
}
