package com.bank.loan.domain.port;

import com.bank.loan.domain.InstallmentAllocation;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.Repayment;
import com.bank.loan.domain.port.in.ApplyForLoanCommand;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.bank.loan.domain.port.in.RepayLoanCommand;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariants of the commands and records that cross the ports. */
class PortTypesTest {

    private static final LoanId LOAN = LoanId.of("LOAN-PORT-1");
    private static final Money HUNDRED = Money.aed(new BigDecimal("100.00"));

    @Test
    void repayCommandNormalisesABlankKeyAndNeedsAScopeForAKey() {
        RepayLoanCommand blank = new RepayLoanCommand(LOAN, HUNDRED, "CUST-1", "  ");
        assertThat(blank.hasIdempotencyKey()).isFalse();
        assertThat(blank.requestScope()).isNull();

        RepayLoanCommand keyed = new RepayLoanCommand(LOAN, HUNDRED, "CUST-1", "k-1");
        assertThat(keyed.hasIdempotencyKey()).isTrue();
        assertThat(keyed.requestScope()).isEqualTo("CUST-1");

        assertThat(RepayLoanCommand.withoutKey(LOAN, HUNDRED).hasIdempotencyKey()).isFalse();
        assertThatThrownBy(() -> new RepayLoanCommand(LOAN, HUNDRED, null, "k-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepayLoanCommand(null, HUNDRED, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RepayLoanCommand(LOAN, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void applyAndCompletedPaymentCommandsRequireEveryField() {
        CustomerId customer = CustomerId.of("CUST-PORT-1");
        InterestRate rate = InterestRate.of(new BigDecimal("6.0"));
        LoanTerm term = LoanTerm.ofMonths(12);
        assertThat(new ApplyForLoanCommand(customer, HUNDRED, rate, term).principal()).isEqualTo(HUNDRED);
        assertThatThrownBy(() -> new ApplyForLoanCommand(null, HUNDRED, rate, term)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApplyForLoanCommand(customer, null, rate, term)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApplyForLoanCommand(customer, HUNDRED, null, term)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApplyForLoanCommand(customer, HUNDRED, rate, null)).isInstanceOf(NullPointerException.class);

        PaymentId payment = PaymentId.of("PAY-PORT-1");
        assertThat(new RecordCompletedLoanPaymentCommand(payment, LOAN, HUNDRED).paymentId()).isEqualTo(payment);
        assertThatThrownBy(() -> new RecordCompletedLoanPaymentCommand(null, LOAN, HUNDRED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordCompletedLoanPaymentCommand(payment, null, HUNDRED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordCompletedLoanPaymentCommand(payment, LOAN, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void repaymentKeepsKeyAndScopeTogetherAndCopiesItsAllocations() {
        InstallmentAllocation first = new InstallmentAllocation(1, HUNDRED, Money.aed(new BigDecimal("90.00")),
            Money.aed(new BigDecimal("10.00")));
        Repayment repayment = new Repayment(PaymentId.of("PAY-R"), LOAN, HUNDRED, new java.util.ArrayList<>(List.of(first)),
            false, Instant.parse("2026-10-08T06:00:00Z"), Repayment.Source.MONOLITH, null, null);

        assertThat(repayment.allocations()).containsExactly(first);
        assertThatThrownBy(() -> repayment.allocations().add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Repayment.Source.values()).containsExactly(
            Repayment.Source.API, Repayment.Source.PAYMENT_EVENT, Repayment.Source.MONOLITH);
        assertThatThrownBy(() -> new Repayment(PaymentId.of("PAY-R"), LOAN, HUNDRED, List.of(), false, Instant.now(),
                Repayment.Source.API, "CUST-1", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Repayment(PaymentId.of("PAY-R"), LOAN, HUNDRED, List.of(), false, null,
                Repayment.Source.API, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allocationMustAddUpAndPointAtAnInstallment() {
        assertThatThrownBy(() -> new InstallmentAllocation(1, HUNDRED, HUNDRED, Money.aed(new BigDecimal("1.00"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstallmentAllocation(0, HUNDRED, HUNDRED, Money.aed(BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creditOutcomesAndFailuresCarryWhatTheCallerNeeds() {
        assertThat(CreditDecision.valueOf("REFUSED")).isEqualTo(CreditDecision.REFUSED);
        assertThat(new CreditCustomerNotFoundException("CUST-9").getCustomerId()).isEqualTo("CUST-9");
        RuntimeException cause = new RuntimeException("timeout");
        assertThat(new CustomerCreditUnavailableException("down", cause).getCause()).isSameAs(cause);
        assertThat(new CustomerCreditUnavailableException("down")).hasMessage("down");
    }
}
