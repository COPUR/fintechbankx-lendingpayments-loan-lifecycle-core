package com.bank.loan.infrastructure.messaging;

/**
 * A consumed record does not match the provider's contract (not JSON, wrong
 * eventType, missing field). Retrying cannot heal it, so it goes straight to
 * the dead-letter topic.
 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }

    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
