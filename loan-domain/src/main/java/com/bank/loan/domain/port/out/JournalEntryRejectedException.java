package com.bank.loan.domain.port.out;

/**
 * The accounts ledger refused the journal entry as sent (422 INSUFFICIENT_FUNDS,
 * ACCOUNT_NOT_OPERATIONAL or UNBALANCED_ENTRY; 409 IDEMPOTENCY_KEY_REUSED; an
 * unknown account; an invalid request). Re-sending the same entry will not
 * change the answer. Not "ledger unavailable". The message carries the
 * ledger's code only, never the ledger's text, accounts or amounts.
 */
public class JournalEntryRejectedException extends RuntimeException {

    private final String code;

    public JournalEntryRejectedException(String code, String message) {
        this(code, message, null);
    }

    public JournalEntryRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
