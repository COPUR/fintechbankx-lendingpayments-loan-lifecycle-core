package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanStatus;
import com.bank.loan.domain.LoanTerm;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit level: the adapter's version check and mapping calls. The SQL itself
 * is exercised against PostgreSQL by LoanLifecycleServiceIT.
 */
class JpaLoanRepositoryAdapterTest {

    private final SpringDataLoanRepository rows = mock(SpringDataLoanRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    private final JpaLoanRepositoryAdapter adapter = new JpaLoanRepositoryAdapter(rows, clock);

    @Test
    void newLoanIsInsertedAndTakesTheStoredVersion() throws Exception {
        Loan loan = loan("LOAN-JPA-1");
        when(rows.findWithInstallmentsByLoanId("LOAN-JPA-1")).thenReturn(Optional.empty());
        when(rows.saveAndFlush(any(LoanJpaEntity.class))).thenAnswer(invocation -> withVersion(invocation.getArgument(0), 0L));

        adapter.save(loan);

        assertThat(loan.getVersion()).isZero();
    }

    @Test
    void staleVersionIsRefused() throws Exception {
        Loan loan = loan("LOAN-JPA-2");
        loan.setVersion(1L);
        LoanJpaEntity stored = withVersion(LoanPersistenceMapper.newEntity(loan), 2L);
        when(rows.findWithInstallmentsByLoanId("LOAN-JPA-2")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> adapter.save(loan)).isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("version 2");
    }

    @Test
    void currentVersionIsCopiedIntoTheStoredRow() throws Exception {
        Loan loan = loan("LOAN-JPA-3");
        loan.setVersion(4L);
        LoanJpaEntity stored = withVersion(LoanPersistenceMapper.newEntity(loan), 4L);
        when(rows.findWithInstallmentsByLoanId("LOAN-JPA-3")).thenReturn(Optional.of(stored));
        when(rows.saveAndFlush(stored)).thenAnswer(invocation -> withVersion(stored, 5L));
        loan.approve();

        adapter.save(loan);

        assertThat(stored.getStatus()).isEqualTo("APPROVED");
        assertThat(loan.getVersion()).isEqualTo(5L);
    }

    @Test
    void readsMapRowsBackToLoans() {
        LoanJpaEntity row = LoanPersistenceMapper.newEntity(loan("LOAN-JPA-4"));
        when(rows.findWithInstallmentsByLoanId("LOAN-JPA-4")).thenReturn(Optional.of(row));
        when(rows.findByCustomerIdOrderByCreatedAtDesc("CUST-JPA")).thenReturn(List.of(row));
        when(rows.findByStatus("CREATED")).thenReturn(List.of(row));
        when(rows.existsById("LOAN-JPA-4")).thenReturn(true);
        when(rows.findOverdue(anyCollection(), eq(LocalDate.of(2026, 10, 8)))).thenReturn(List.of(row));

        assertThat(adapter.findById(LoanId.of("LOAN-JPA-4"))).map(Loan::getId).contains(LoanId.of("LOAN-JPA-4"));
        assertThat(adapter.findByCustomerId(CustomerId.of("CUST-JPA"))).hasSize(1);
        assertThat(adapter.findByStatus(LoanStatus.CREATED)).hasSize(1);
        assertThat(adapter.existsById(LoanId.of("LOAN-JPA-4"))).isTrue();
        assertThat(adapter.findOverdueLoans()).hasSize(1);
        adapter.delete(loan("LOAN-JPA-4"));
        verify(rows).deleteById("LOAN-JPA-4");
    }

    private static Loan loan(String id) {
        return Loan.create(LoanId.of(id), CustomerId.of("CUST-JPA"), Money.aed(new BigDecimal("6000.00")),
            InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(6));
    }

    private static LoanJpaEntity withVersion(LoanJpaEntity entity, Long version) throws Exception {
        Field field = LoanJpaEntity.class.getDeclaredField("version");
        field.setAccessible(true);
        field.set(entity, version);
        return entity;
    }
}
