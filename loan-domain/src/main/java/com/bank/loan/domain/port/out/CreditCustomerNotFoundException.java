package com.bank.loan.domain.port.out;

/**
 * The customer service does not know the loan's customer (404
 * CUSTOMER_NOT_FOUND). Not a credit refusal: the loan cannot proceed until the
 * customer exists in the customer context.
 */
public class CreditCustomerNotFoundException extends RuntimeException {

    private final String customerId;

    public CreditCustomerNotFoundException(String customerId) {
        // No customer id in the message (API bodies, logs); callers that need it use getCustomerId().
        super("The loan's customer is not known to the customer service");
        this.customerId = customerId;
    }

    public String getCustomerId() {
        return customerId;
    }
}
