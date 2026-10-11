package com.bank.loan.domain.port.out;

/**
 * The loan's credit reservation is held for an operator: the customer
 * service refused a compensating release of it (422
 * RELEASE_EXCEEDS_RESERVATION), so this service will not reserve, release or
 * re-send anything for the loan until an operator has resolved the row.
 * Not retryable: a retry gets the same answer until then. Not "customer
 * service unavailable" either; the customer service did answer.
 */
public class CreditReservationNeedsOperatorException extends RuntimeException {

    private final String reason;

    public CreditReservationNeedsOperatorException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public CreditReservationNeedsOperatorException(String reason, String message) {
        this(reason, message, null);
    }

    /** The customer service's code that put the reservation on hold, for example RELEASE_EXCEEDS_RESERVATION. */
    public String getReason() {
        return reason;
    }
}
