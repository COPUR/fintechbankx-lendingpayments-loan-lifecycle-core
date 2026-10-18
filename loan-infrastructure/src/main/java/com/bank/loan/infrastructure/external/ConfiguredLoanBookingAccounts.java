package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.LoanBookingAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Currency;

/**
 * Loan booking accounts from configuration (journal.loan.*): one currency, one
 * receivable account and one clearing account, all three unset by default.
 * Set partly, the service does not start. Unset, every disbursement is refused
 * (fail closed), and so is a disbursement in any other currency.
 */
@Component
public class ConfiguredLoanBookingAccounts implements LoanBookingAccounts {

    private final String currency;
    private final String receivableAccountId;
    private final String clearingAccountId;

    public ConfiguredLoanBookingAccounts(@Value("${journal.loan.currency:}") String currency,
                                         @Value("${journal.loan.receivable-account-id:}") String receivableAccountId,
                                         @Value("${journal.loan.clearing-account-id:}") String clearingAccountId) {
        this.currency = currency.trim();
        this.receivableAccountId = receivableAccountId.trim();
        this.clearingAccountId = clearingAccountId.trim();
        boolean anySet = !this.currency.isEmpty() || !this.receivableAccountId.isEmpty() || !this.clearingAccountId.isEmpty();
        boolean allSet = !this.currency.isEmpty() && !this.receivableAccountId.isEmpty() && !this.clearingAccountId.isEmpty();
        if (anySet && !allSet) {
            throw new IllegalStateException(
                "journal.loan.currency, journal.loan.receivable-account-id and journal.loan.clearing-account-id must be set together");
        }
        if (allSet) {
            Currency.getInstance(this.currency);
            new Accounts(this.receivableAccountId, this.clearingAccountId);
        }
    }

    @Override
    public Accounts forCurrency(Currency requested) {
        if (currency.isEmpty()) {
            throw new AccountsLedgerUnavailableException("No loan booking accounts are configured; nothing was booked");
        }
        if (!requested.getCurrencyCode().equals(currency)) {
            throw new AccountsLedgerUnavailableException(
                "No loan booking accounts are configured for " + requested.getCurrencyCode() + "; nothing was booked");
        }
        return new Accounts(receivableAccountId, clearingAccountId);
    }
}
