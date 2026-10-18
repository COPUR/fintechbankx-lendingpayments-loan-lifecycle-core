package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.LoanBookingAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Currency;

/**
 * Loan booking accounts from configuration (journal.loan.*): one currency, one
 * receivable account and one clearing account, all three unset by default, and
 * one interest income account for repayments. Set partly, the service does not
 * start. Unset, every disbursement is refused (fail closed), and so is a
 * repayment with interest, as is any booking in another currency.
 */
@Component
public class ConfiguredLoanBookingAccounts implements LoanBookingAccounts {

    private final String currency;
    private final String receivableAccountId;
    private final String clearingAccountId;
    private final String interestIncomeAccountId;

    public ConfiguredLoanBookingAccounts(@Value("${journal.loan.currency:}") String currency,
                                         @Value("${journal.loan.receivable-account-id:}") String receivableAccountId,
                                         @Value("${journal.loan.clearing-account-id:}") String clearingAccountId,
                                         @Value("${journal.loan.interest-income-account-id:}") String interestIncomeAccountId) {
        this.currency = currency.trim();
        this.receivableAccountId = receivableAccountId.trim();
        this.clearingAccountId = clearingAccountId.trim();
        this.interestIncomeAccountId = interestIncomeAccountId.trim();
        boolean anySet = !this.currency.isEmpty() || !this.receivableAccountId.isEmpty() || !this.clearingAccountId.isEmpty();
        boolean allSet = !this.currency.isEmpty() && !this.receivableAccountId.isEmpty() && !this.clearingAccountId.isEmpty();
        if (anySet && !allSet) {
            throw new IllegalStateException(
                "journal.loan.currency, journal.loan.receivable-account-id and journal.loan.clearing-account-id must be set together");
        }
        if (allSet) {
            Currency.getInstance(this.currency);
            // Validates that the receivable and clearing accounts differ.
            new Accounts(this.receivableAccountId, this.clearingAccountId);
        }
        if (!this.interestIncomeAccountId.isEmpty() && this.interestIncomeAccountId.equals(this.receivableAccountId)) {
            throw new IllegalStateException("journal.loan.interest-income-account-id must not be the receivable account");
        }
    }

    @Override
    public Accounts forCurrency(Currency requested) {
        requireConfiguredCurrency(requested);
        return new Accounts(receivableAccountId, clearingAccountId);
    }

    @Override
    public String interestIncomeAccountId(Currency requested) {
        requireConfiguredCurrency(requested);
        if (interestIncomeAccountId.isEmpty()) {
            throw new AccountsLedgerUnavailableException("No interest income account is configured; nothing was booked");
        }
        return interestIncomeAccountId;
    }

    private void requireConfiguredCurrency(Currency requested) {
        if (currency.isEmpty()) {
            throw new AccountsLedgerUnavailableException("No loan booking accounts are configured; nothing was booked");
        }
        if (!requested.getCurrencyCode().equals(currency)) {
            throw new AccountsLedgerUnavailableException(
                "No loan booking accounts are configured for " + requested.getCurrencyCode() + "; nothing was booked");
        }
    }
}
