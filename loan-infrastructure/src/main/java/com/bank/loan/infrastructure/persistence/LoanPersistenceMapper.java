package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.InstallmentStatus;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanInstallment;
import com.bank.loan.domain.LoanStatus;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.RateBasis;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps the Loan aggregate to and from its tables. Hand-written rather than
 * MapStruct because the aggregate has no setters and is rebuilt through
 * {@link Loan#rehydrate}.
 */
final class LoanPersistenceMapper {

    private LoanPersistenceMapper() {
    }

    static Loan toDomain(LoanJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        LoanId loanId = LoanId.of(entity.getLoanId());
        CustomerId customerId = CustomerId.of(entity.getCustomerId());
        List<LoanInstallment> installments = entity.getInstallments().stream()
            .map(row -> LoanInstallment.rehydrate(
                loanId,
                customerId,
                row.getInstallmentNumber(),
                Money.of(row.getAmount(), Currency.getInstance(row.getCurrency())),
                Money.of(row.getPrincipalAmount(), Currency.getInstance(row.getCurrency())),
                Money.of(row.getInterestAmount(), Currency.getInstance(row.getCurrency())),
                row.getDueDate(),
                Money.of(row.getPaidAmount(), Currency.getInstance(row.getCurrency())),
                row.getPaidAt(),
                InstallmentStatus.valueOf(row.getStatus())))
            .toList();
        return Loan.rehydrate(
            loanId,
            customerId,
            Money.of(entity.getPrincipalAmount(), currency),
            InterestRate.of(entity.getAnnualInterestRate()),
            RateBasis.valueOf(entity.getRateBasis()),
            LoanTerm.ofMonths(entity.getTermMonths()),
            LoanStatus.valueOf(entity.getStatus()),
            entity.getApplicationDate(),
            entity.getApprovalDate(),
            entity.getDisbursementDate(),
            entity.getMaturityDate(),
            Money.of(entity.getOutstandingBalance(), currency),
            installments,
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getVersion());
    }

    static LoanJpaEntity newEntity(Loan loan) {
        LoanJpaEntity entity = new LoanJpaEntity(loan.getId().getValue());
        copyInto(loan, entity);
        return entity;
    }

    static void copyInto(Loan loan, LoanJpaEntity entity) {
        entity.setCustomerId(loan.getCustomerId().getValue());
        entity.setPrincipalAmount(loan.getPrincipalAmount().getAmount());
        entity.setCurrency(loan.getPrincipalAmount().getCurrency().getCurrencyCode());
        entity.setAnnualInterestRate(loan.getInterestRate().getAnnualRate());
        entity.setRateBasis(loan.getRateBasis().name());
        entity.setTermMonths(loan.getLoanTerm().getMonths());
        entity.setStatus(loan.getStatus().name());
        entity.setApplicationDate(loan.getApplicationDate());
        entity.setApprovalDate(loan.getApprovalDate());
        entity.setDisbursementDate(loan.getDisbursementDate());
        entity.setMaturityDate(loan.getMaturityDate());
        entity.setOutstandingBalance(loan.getOutstandingBalance().getAmount());
        entity.setCreatedAt(loan.getCreatedAt());
        entity.setUpdatedAt(loan.getUpdatedAt());
        copyInstallments(loan.getInstallments(), entity);
    }

    private static void copyInstallments(List<LoanInstallment> installments, LoanJpaEntity entity) {
        Map<Integer, LoanInstallmentJpaEntity> existing = entity.getInstallments().stream()
            .collect(Collectors.toMap(LoanInstallmentJpaEntity::getInstallmentNumber, Function.identity()));
        Map<Integer, LoanInstallment> wanted = installments.stream()
            .collect(Collectors.toMap(LoanInstallment::getInstallmentNumber, Function.identity()));

        entity.getInstallments().removeIf(row -> !wanted.containsKey(row.getInstallmentNumber()));
        for (LoanInstallment installment : installments) {
            LoanInstallmentJpaEntity row = existing.get(installment.getInstallmentNumber());
            if (row == null) {
                row = new LoanInstallmentJpaEntity(entity, installment.getInstallmentNumber());
                entity.getInstallments().add(row);
            }
            row.setAmount(installment.getAmount().getAmount());
            row.setPrincipalAmount(installment.getPrincipalComponent().getAmount());
            row.setInterestAmount(installment.getInterestComponent().getAmount());
            row.setPaidAmount(installment.getPaidAmount().getAmount());
            row.setCurrency(installment.getAmount().getCurrency().getCurrencyCode());
            row.setDueDate(installment.getDueDate());
            row.setPaidAt(installment.getPaidDate());
            row.setStatus(installment.getStatus().name());
        }
    }
}
