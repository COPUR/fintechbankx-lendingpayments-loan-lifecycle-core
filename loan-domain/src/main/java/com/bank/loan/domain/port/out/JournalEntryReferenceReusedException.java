package com.bank.loan.domain.port.out;

/**
 * The accounts ledger already holds an entry under this reference with other
 * lines (409 IDEMPOTENCY_KEY_REUSED): an earlier attempt booked it, for example
 * one that timed out before the booking accounts were reconfigured. Refused like
 * any other entry, but the entry exists, so a disbursement raises
 * {@link UnresolvedBookingSignal} for reconciliation (runbook section 10).
 */
public class JournalEntryReferenceReusedException extends JournalEntryRejectedException {

    public JournalEntryReferenceReusedException(String code, String message) {
        super(code, message);
    }
}
