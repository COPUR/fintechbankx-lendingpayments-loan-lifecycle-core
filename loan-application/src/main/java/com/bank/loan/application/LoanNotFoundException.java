package com.bank.loan.application;

/**
 * Exception thrown when a loan cannot be found
 */
public class LoanNotFoundException extends RuntimeException {
    
    public LoanNotFoundException(String message) {
        super(message);
    }
    
    /** No identifier in the message: it reaches API bodies and logs. */
    public LoanNotFoundException(String loanId, Throwable cause) {
        super("Loan not found", cause);
    }

    public static LoanNotFoundException withId(String loanId) {
        return new LoanNotFoundException("Loan not found");
    }
}