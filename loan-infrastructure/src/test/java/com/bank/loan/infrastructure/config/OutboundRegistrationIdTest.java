package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each outbound adapter asks for the token of its own registration. The customer adapter uses
 * customer-service, which carries no ledger scope; the journal adapter uses accounts-ledger, the only
 * registration that asks for aud-svc-acc-accounts-ledger. A bean that reads the other key, or
 * another default, fails here.
 */
class OutboundRegistrationIdTest {

    private static final String CUSTOMER_REGISTRATION = "loan.customer-credit.client-registration-id=customer-service";
    private static final String LEDGER_REGISTRATION = "journal.client-registration-id=accounts-ledger";

    @Test
    void theCustomerAdapterReadsTheCustomerRegistration() throws NoSuchMethodException {
        assertThat(registrationOf(CustomerCreditClientConfiguration.class, "customerCreditService"))
            .isEqualTo(CUSTOMER_REGISTRATION);
    }

    @Test
    void theJournalAdapterReadsTheLedgerRegistration() throws NoSuchMethodException {
        assertThat(registrationOf(JournalClientConfiguration.class, "journalEntryPort"))
            .isEqualTo(LEDGER_REGISTRATION);
    }

    /** The key and default of the registration id that a bean method reads, as key=default. */
    private static String registrationOf(Class<?> configuration, String beanMethod) throws NoSuchMethodException {
        Method method = Arrays.stream(configuration.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(beanMethod))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(configuration.getName() + "#" + beanMethod));
        for (Parameter parameter : method.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null && value.value().contains("registration-id")) {
                return value.value().replaceAll("^\\$\\{|}$", "").replaceFirst(":", "=");
            }
        }
        throw new AssertionError(beanMethod + " reads no registration id");
    }
}
