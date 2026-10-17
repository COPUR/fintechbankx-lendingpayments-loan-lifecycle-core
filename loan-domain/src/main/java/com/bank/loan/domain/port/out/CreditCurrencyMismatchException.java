package com.bank.loan.domain.port.out;

import java.util.Currency;

/**
 * The loan is in a currency the customer's credit is not held in. Amounts in
 * different currencies are never compared or converted; the request is
 * invalid rather than "insufficient credit".
 */
public class CreditCurrencyMismatchException extends RuntimeException {

    private final Currency requested;
    private final Currency creditCurrency;

    public CreditCurrencyMismatchException(Currency requested, Currency creditCurrency) {
        super("The loan is in " + requested.getCurrencyCode() + " but the customer's credit is held in "
            + (creditCurrency == null ? "another currency" : creditCurrency.getCurrencyCode()));
        this.requested = requested;
        this.creditCurrency = creditCurrency;
    }

    public Currency getRequested() {
        return requested;
    }

    /** null when the customer service only said the currencies differ */
    public Currency getCreditCurrency() {
        return creditCurrency;
    }
}
