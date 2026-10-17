package com.bank.loan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Row of sc_ln_loan_lifecycle.loan_installment. Unique per (loan_id, installment_number).
 */
@Entity
@Table(name = "loan_installment")
public class LoanInstallmentJpaEntity {

    @Id
    @Column(name = "installment_id")
    private UUID installmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false, updatable = false)
    private LoanJpaEntity loan;

    @Column(name = "installment_number", nullable = false, updatable = false)
    private int installmentNumber;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    protected LoanInstallmentJpaEntity() {
    }

    LoanInstallmentJpaEntity(LoanJpaEntity loan, int installmentNumber) {
        this.installmentId = UUID.randomUUID();
        this.loan = loan;
        this.installmentNumber = installmentNumber;
    }

    public UUID getInstallmentId() { return installmentId; }
    public int getInstallmentNumber() { return installmentNumber; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public BigDecimal getInterestAmount() { return interestAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getCurrency() { return currency; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public String getStatus() { return status; }

    void setAmount(BigDecimal amount) { this.amount = amount; }
    void setPrincipalAmount(BigDecimal principalAmount) { this.principalAmount = principalAmount; }
    void setInterestAmount(BigDecimal interestAmount) { this.interestAmount = interestAmount; }
    void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    void setCurrency(String currency) { this.currency = currency; }
    void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    void setStatus(String status) { this.status = status; }
}
