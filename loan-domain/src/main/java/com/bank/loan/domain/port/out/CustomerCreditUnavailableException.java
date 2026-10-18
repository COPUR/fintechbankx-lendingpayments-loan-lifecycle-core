package com.bank.loan.domain.port.out;

/**
 * The customer service could not answer a credit question (unreachable,
 * timed out, rejected this service's credentials, or kept reporting
 * concurrent updates). The command is not applied; the caller may retry.
 */
public class CustomerCreditUnavailableException extends RuntimeException {

    public CustomerCreditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CustomerCreditUnavailableException(String message) {
        super(message);
    }
}
