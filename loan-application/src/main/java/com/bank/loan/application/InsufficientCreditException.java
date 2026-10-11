package com.bank.loan.application;

/**
 * Exception thrown when customer has insufficient credit for a loan
 */
public class InsufficientCreditException extends RuntimeException {
    
    public InsufficientCreditException(String message) {
        super(message);
    }
    
    public InsufficientCreditException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /** The message carries amounts only, never the customer id (it reaches API bodies and logs). */
    public static InsufficientCreditException forCustomer(String customerId, String requestedAmount, String availableAmount) {
        return new InsufficientCreditException(
            String.format("The customer has insufficient credit. Requested: %s, Available: %s",
                requestedAmount, availableAmount));
    }
}