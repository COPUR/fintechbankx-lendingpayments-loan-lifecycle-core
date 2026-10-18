package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.LoanBookingAccounts;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredLoanBookingAccountsTest {

    @Test
    void unsetAccountsRefuseEveryCurrency() {
        ConfiguredLoanBookingAccounts accounts = new ConfiguredLoanBookingAccounts("", "", "");

        assertThatThrownBy(() -> accounts.forCurrency(Currency.getInstance("AED")))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("nothing was booked");
    }

    @Test
    void configuredAccountsBookTheConfiguredCurrencyOnly() {
        ConfiguredLoanBookingAccounts accounts =
            new ConfiguredLoanBookingAccounts("AED", "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING");

        assertThat(accounts.forCurrency(Currency.getInstance("AED")))
            .isEqualTo(new LoanBookingAccounts.Accounts("LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING"));
        assertThatThrownBy(() -> accounts.forCurrency(Currency.getInstance("USD")))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("USD");
    }

    @Test
    void partlyConfiguredAccountsDoNotStart() {
        assertThatThrownBy(() -> new ConfiguredLoanBookingAccounts("AED", "LOAN-RECEIVABLE", ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be set together");
    }

    @Test
    void receivableAndClearingAccountsMustDiffer() {
        assertThatThrownBy(() -> new ConfiguredLoanBookingAccounts("AED", "SAME-ACCOUNT", "SAME-ACCOUNT"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
