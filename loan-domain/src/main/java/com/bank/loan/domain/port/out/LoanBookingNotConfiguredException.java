package com.bank.loan.domain.port.out;

/**
 * This service has no booking accounts (or no interest income account) for the
 * currency of the booking. A configuration fault: nothing was sent to the
 * ledger, and retrying the same request cannot succeed until operations
 * configure the service. Answered as a 422, not as a retryable 503.
 */
public class LoanBookingNotConfiguredException extends RuntimeException {

    public LoanBookingNotConfiguredException(String message) {
        super(message);
    }
}
