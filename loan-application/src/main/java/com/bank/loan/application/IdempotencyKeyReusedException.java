package com.bank.loan.application;

/**
 * An x-idempotency-key was sent again for a different repayment (another
 * loan or another amount) by the same caller.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency key was already used for a different repayment: " + idempotencyKey);
    }
}
