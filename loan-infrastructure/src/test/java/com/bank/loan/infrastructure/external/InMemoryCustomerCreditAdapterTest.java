package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCustomerCreditAdapterTest {

    private static final LoanId LOAN = LoanId.of("LOAN-MEM-1");
    private final InMemoryCustomerCreditAdapter adapter = new InMemoryCustomerCreditAdapter("AED");

    @Test
    void creditIsHeldInTheConfiguredCurrencyOnly() {
        CustomerId customer = CustomerId.of("CUST-12345678");
        assertThat(adapter.hasAvailableCredit(customer, aed("500.00"))).isTrue();
        assertThatThrownBy(() -> adapter.hasAvailableCredit(customer, Money.usd(new BigDecimal("500.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class)
            .hasMessage("The loan is in USD but the customer's credit is held in AED");
        assertThat(adapter.hasAvailableCredit(CustomerId.of("CUST-11111111"), aed("6000.00"))).isFalse();
        assertThat(adapter.getAvailableCredit(customer)).isEqualTo(Money.of(new BigDecimal("100000"), aed("1").getCurrency()));
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, customer, Money.usd(new BigDecimal("1.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class);
        assertThatThrownBy(() -> adapter.releaseCredit(LOAN, customer, Money.usd(new BigDecimal("1.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class);
    }

    @Test
    void reserveCancelAndReleaseMoveAvailableCredit() {
        CustomerId customer = CustomerId.of("CUST-12345678");

        assertThat(adapter.reserveCredit(LOAN, customer, aed("2000.00"))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.getAvailableCredit(customer).getAmount()).isEqualByComparingTo("98000.00");
        assertThat(adapter.cancelReservation(LOAN, customer, aed("2000.00"))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.getAvailableCredit(customer).getAmount()).isEqualByComparingTo("100000.00");
        assertThat(adapter.reserveCredit(LOAN, customer, aed("2000.00"))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.releaseCredit(LOAN, customer, aed("2000.00"))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.getAvailableCredit(customer).getAmount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void reservationAboveAvailableCreditIsRefusedAndReleaseNeverGoesBelowZero() {
        CustomerId bounded = CustomerId.of("CUST-87654321");
        assertThat(adapter.reserveCredit(LOAN, bounded, aed("60000.00"))).isEqualTo(CreditDecision.REFUSED);
        assertThat(adapter.releaseCredit(LOAN, bounded, aed("999999.00"))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.getAvailableCredit(bounded).getAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    void unknownCustomerIsNotFoundNotARefusal() {
        CustomerId unknown = CustomerId.of("CUST-UNKNOWN");
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, unknown, aed("1.00")))
            .isInstanceOf(CreditCustomerNotFoundException.class);
        assertThatThrownBy(() -> adapter.getAvailableCredit(unknown)).isInstanceOf(CreditCustomerNotFoundException.class);
    }

    @Test
    void ledgerCurrencyIsRequired() {
        assertThatThrownBy(() -> new InMemoryCustomerCreditAdapter(""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CUSTOMER_CREDIT_LEDGER_CURRENCY");
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
