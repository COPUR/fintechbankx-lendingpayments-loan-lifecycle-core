package com.bank.loan.domain.port.out;

/**
 * Out-port to the core-banking accounts ledger (svc-acc-accounts-ledger,
 * ADR-030), which books double entries and moves account balances.
 * Implemented by an anti-corruption adapter in loan-infrastructure.
 *
 * The entry's {@code reference} is the idempotency key: the same reference
 * with the same lines replays the first booking, so a retried command never
 * moves money twice. Callers must not hold a database transaction open while
 * calling this port: it is a remote call.
 *
 * <ul>
 *   <li>{@link JournalEntryRejectedException}: the ledger refused the entry
 *       as sent (INSUFFICIENT_FUNDS, ACCOUNT_NOT_OPERATIONAL, UNBALANCED_ENTRY,
 *       IDEMPOTENCY_KEY_REUSED, an unknown account, an invalid request);
 *       re-sending the same entry will not change the answer.</li>
 *   <li>{@link AccountsLedgerUnavailableException}: no usable answer (down,
 *       timeout, no service token, rejected credentials, kept reporting
 *       concurrent updates). Whether the entry was booked is unknown; a retry
 *       with the same reference is safe.</li>
 * </ul>
 */
public interface JournalEntryPort {

    /** Books the entry, or replays the booking already made under its reference. Never returns on failure. */
    JournalEntryReceipt post(JournalEntry entry);
}
