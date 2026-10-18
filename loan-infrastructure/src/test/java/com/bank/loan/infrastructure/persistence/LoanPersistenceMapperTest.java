package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.InstallmentStatus;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanInstallment;
import com.bank.loan.domain.LoanStatus;
import com.bank.loan.domain.LoanTerm;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPersistenceMapperTest {

    @Test
    void loanSurvivesARoundTripThroughTheEntity() {
        Loan loan = Loan.createWithInstallments(LoanId.of("LOAN-MAP-1"), CustomerId.of("CUST-MAP"),
            Money.aed(new BigDecimal("24000.00")), InterestRate.of(new BigDecimal("5.5")), LoanTerm.ofMonths(12));
        loan.approve();
        loan.disburse();
        loan.getInstallments().getFirst();

        LoanJpaEntity entity = LoanPersistenceMapper.newEntity(loan);
        Loan back = LoanPersistenceMapper.toDomain(entity);

        assertThat(back.getId()).isEqualTo(loan.getId());
        assertThat(back.getCustomerId()).isEqualTo(loan.getCustomerId());
        assertThat(back.getPrincipalAmount()).isEqualTo(loan.getPrincipalAmount());
        assertThat(back.getInterestRate()).isEqualTo(loan.getInterestRate());
        assertThat(back.getLoanTerm()).isEqualTo(loan.getLoanTerm());
        assertThat(back.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(back.getMaturityDate()).isEqualTo(loan.getMaturityDate());
        assertThat(back.getOutstandingBalance()).isEqualTo(loan.getOutstandingBalance());
        assertThat(back.getInstallments()).hasSize(12);
        assertThat(back.getTotalInstallmentAmount()).isEqualTo(loan.getTotalInstallmentAmount());
        assertThat(back.getDomainEvents()).isEmpty();
    }

    @Test
    void updatingAnEntityKeepsInstallmentRowsAndCopiesPaymentState() {
        LoanId id = LoanId.of("LOAN-MAP-2");
        CustomerId customer = CustomerId.of("CUST-MAP");
        Money amount = Money.aed(new BigDecimal("1000.00"));
        Loan original = Loan.createWithInstallments(id, customer, Money.aed(new BigDecimal("6000.00")),
            InterestRate.zero(), LoanTerm.ofMonths(6));
        LoanJpaEntity entity = LoanPersistenceMapper.newEntity(original);
        var firstRowId = entity.getInstallments().getFirst().getInstallmentId();

        LoanInstallment paid = LoanInstallment.rehydrate(id, customer, 1, amount,
            original.getInstallments().getFirst().getDueDate(), amount, java.time.LocalDateTime.now(), InstallmentStatus.PAID);
        java.util.List<LoanInstallment> schedule = new java.util.ArrayList<>(original.getInstallments());
        schedule.set(0, paid);
        Loan changed = Loan.rehydrate(id, customer, original.getPrincipalAmount(), original.getInterestRate(),
            original.getLoanTerm(), LoanStatus.DISBURSED, original.getApplicationDate(), null, null, null,
            Money.aed(new BigDecimal("5000.00")), schedule, original.getCreatedAt(), original.getUpdatedAt(), 0L);

        LoanPersistenceMapper.copyInto(changed, entity);

        assertThat(entity.getInstallments()).hasSize(6);
        assertThat(entity.getInstallments().getFirst().getInstallmentId()).isEqualTo(firstRowId);
        assertThat(entity.getInstallments().getFirst().getStatus()).isEqualTo("PAID");
        assertThat(entity.getInstallments().getFirst().getPaidAmount()).isEqualByComparingTo("1000.00");
        assertThat(entity.getOutstandingBalance()).isEqualByComparingTo("5000.00");
    }
}
