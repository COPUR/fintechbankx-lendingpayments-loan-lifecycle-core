package com.bank.loan.domain.port.out;

/**
 * The customer service rejected a credit movement as invalid (400): the
 * request will never be accepted as sent (for example a malformed currency),
 * so retrying is pointless. Not "customer service unavailable".
 */
public class CreditMovementRejectedException extends RuntimeException {

    public CreditMovementRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
