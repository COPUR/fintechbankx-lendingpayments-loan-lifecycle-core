package com.bank.loan.domain.port.out;

import java.util.Currency;
import java.util.Objects;

/**
 * The accounts ledger accounts a disbursement is booked against: the loan
 * receivable, debited by the principal, and the disbursement clearing account,
 * credited by it. Both are INTERNAL ledger accounts that operations opens
 * (ADR-030). Implemented from configuration in loan-infrastructure.
 *
 * Asked for a currency with no configured accounts, the implementation throws
 * {@link AccountsLedgerUnavailableException}: a disbursement that cannot be
 * booked must not proceed.
 */
public interface LoanBookingAccounts {

    /** The accounts for a disbursement in the given currency. Never returns on failure. */
    Accounts forCurrency(Currency currency);

    /** The two ledger accounts of one disbursement; their ids are never the same. */
    record Accounts(String receivableAccountId, String clearingAccountId) {

        public Accounts {
            Objects.requireNonNull(receivableAccountId, "receivableAccountId");
            Objects.requireNonNull(clearingAccountId, "clearingAccountId");
            if (receivableAccountId.isBlank() || clearingAccountId.isBlank()) {
                throw new IllegalArgumentException("A booking account needs a ledger account id");
            }
            if (receivableAccountId.equals(clearingAccountId)) {
                throw new IllegalArgumentException("The receivable and clearing accounts must differ");
            }
        }
    }
}
