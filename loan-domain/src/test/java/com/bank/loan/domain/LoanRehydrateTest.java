package com.bank.loan.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanRehydrateTest {

    private static final LoanId ID = LoanId.of("LOAN-RH-1");
    private static final CustomerId CUSTOMER = CustomerId.of("CUST-RH");

    @Test
    void rehydratedLoanKeepsStoredStateAndRaisesNoEvents() {
        LoanInstallment paid = LoanInstallment.rehydrate(ID, CUSTOMER, 1, Money.aed(new BigDecimal("500.00")),
            LocalDate.of(2026, 2, 1), Money.aed(new BigDecimal("500.00")), LocalDateTime.of(2026, 1, 30, 9, 0),
            InstallmentStatus.PAID);
        List<LoanInstallment> schedule = new java.util.ArrayList<>(List.of(paid));
        for (int n = 2; n <= 6; n++) {
            schedule.add(LoanInstallment.rehydrate(ID, CUSTOMER, n, Money.aed(new BigDecimal("500.00")),
                LocalDate.of(2026, 1, 1).plusMonths(n), Money.aed(BigDecimal.ZERO), null, InstallmentStatus.PENDING));
        }

        Loan loan = Loan.rehydrate(ID, CUSTOMER, Money.aed(new BigDecimal("3000.00")), InterestRate.of(new BigDecimal("4.0")),
            LoanTerm.ofMonths(6), LoanStatus.DISBURSED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 1, 3), LocalDate.of(2026, 7, 3), Money.aed(new BigDecimal("2500.00")), schedule,
            LocalDateTime.of(2026, 1, 1, 8, 0), LocalDateTime.of(2026, 1, 30, 9, 0), 7L);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(loan.getOutstandingBalance()).isEqualTo(Money.aed(new BigDecimal("2500.00")));
        assertThat(loan.getVersion()).isEqualTo(7L);
        assertThat(loan.getInstallments()).hasSize(6).first().isEqualTo(paid);
        assertThat(loan.getInstallments().getFirst().isPaid()).isTrue();
        assertThat(loan.getDomainEvents()).isEmpty();
    }

    @Test
    void rehydratedLoanStillEnforcesItsRules() {
        Loan loan = Loan.rehydrate(ID, CUSTOMER, Money.aed(new BigDecimal("3000.00")), InterestRate.zero(),
            LoanTerm.ofMonths(6), LoanStatus.DISBURSED, LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 1, 3),
            LocalDate.of(2026, 7, 3), Money.aed(new BigDecimal("1000.00")), List.of(),
            LocalDateTime.now(), LocalDateTime.now(), 2L);

        loan.makePayment(Money.aed(new BigDecimal("1000.00")));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.FULLY_PAID);
        assertThat(loan.getDomainEvents()).hasSize(2);
    }
}
