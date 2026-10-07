package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanRepository;
import com.bank.loan.domain.LoanStatus;
import com.bank.shared.kernel.domain.CustomerId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Out-port adapter for {@link LoanRepository} over the service's own schema
 * (sc_ln_loan_lifecycle). Optimistic locking: a save whose aggregate version
 * differs from the stored row fails instead of overwriting a concurrent change.
 */
@Repository
@Transactional
public class JpaLoanRepositoryAdapter implements LoanRepository {

    private static final List<String> REPAYING_STATUSES = EnumSet.allOf(LoanStatus.class).stream()
        .filter(LoanStatus::canAcceptPayments)
        .map(Enum::name)
        .toList();

    private final SpringDataLoanRepository loans;
    private final Clock clock;

    public JpaLoanRepositoryAdapter(SpringDataLoanRepository loans, Clock clock) {
        this.loans = loans;
        this.clock = clock;
    }

    @Override
    public Loan save(Loan loan) {
        LoanJpaEntity entity = loans.findWithInstallmentsByLoanId(loan.getId().getValue()).orElse(null);
        if (entity == null) {
            entity = LoanPersistenceMapper.newEntity(loan);
        } else {
            if (!Objects.equals(entity.getVersion(), loan.getVersion())) {
                throw new OptimisticLockingFailureException(
                    "Loan " + loan.getId() + " is at version " + entity.getVersion()
                        + " but the change was made on version " + loan.getVersion());
            }
            LoanPersistenceMapper.copyInto(loan, entity);
        }
        LoanJpaEntity saved = loans.saveAndFlush(entity);
        loan.setVersion(saved.getVersion());
        return loan;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Loan> findById(LoanId loanId) {
        return loans.findWithInstallmentsByLoanId(loanId.getValue()).map(LoanPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Loan> findByCustomerId(CustomerId customerId) {
        return loans.findByCustomerIdOrderByCreatedAtDesc(customerId.getValue()).stream()
            .map(LoanPersistenceMapper::toDomain)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Loan> findByStatus(LoanStatus status) {
        return loans.findByStatus(status.name()).stream().map(LoanPersistenceMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(LoanId loanId) {
        return loans.existsById(loanId.getValue());
    }

    @Override
    public void delete(Loan loan) {
        loans.deleteById(loan.getId().getValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Loan> findOverdueLoans() {
        return loans.findOverdue(REPAYING_STATUSES, LocalDate.now(clock)).stream()
            .map(LoanPersistenceMapper::toDomain)
            .toList();
    }
}
