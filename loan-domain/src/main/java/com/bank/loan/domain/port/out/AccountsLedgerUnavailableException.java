package com.bank.loan.domain.port.out;

/**
 * The accounts ledger gave no usable answer to a journal entry (unreachable,
 * timed out, rejected this service's credentials, answered without a booking,
 * or kept reporting concurrent updates). Whether the entry was booked is
 * unknown; a retry with the same reference is safe.
 */
public class AccountsLedgerUnavailableException extends RuntimeException {

    public AccountsLedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountsLedgerUnavailableException(String message) {
        super(message);
    }
}
