package com.bank.loan.infrastructure.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface SpringDataLoanRepository extends JpaRepository<LoanJpaEntity, String> {

    @EntityGraph(attributePaths = "installments")
    Optional<LoanJpaEntity> findWithInstallmentsByLoanId(String loanId);

    @EntityGraph(attributePaths = "installments")
    List<LoanJpaEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    @EntityGraph(attributePaths = "installments")
    List<LoanJpaEntity> findByStatus(String status);

    @EntityGraph(attributePaths = "installments")
    @Query("""
        select l from LoanJpaEntity l
        where l.status in :statuses
          and l.maturityDate < :today
          and l.outstandingBalance > 0
        """)
    List<LoanJpaEntity> findOverdue(@Param("statuses") Collection<String> statuses, @Param("today") LocalDate today);
}
