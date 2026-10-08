package com.bank.loan.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Row of sc_ln_loan_lifecycle.loan. Persistence shape only; the Loan
 * aggregate in loan-domain holds the rules.
 */
@Entity
@Table(name = "loan")
public class LoanJpaEntity {

    @Id
    @Column(name = "loan_id", length = 64)
    private String loanId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal annualInterestRate;

    @Column(name = "rate_basis", nullable = false, length = 16)
    private String rateBasis;

    /** Written only by the backfill; read-only here. */
    @Column(name = "legacy_flat_rate", precision = 19, scale = 6, insertable = false, updatable = false)
    private BigDecimal legacyFlatRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "application_date", nullable = false)
    private LocalDate applicationDate;

    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "outstanding_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingBalance;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "legacy_loan_id", length = 64, updatable = false)
    private String legacyLoanId;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNumber ASC")
    private List<LoanInstallmentJpaEntity> installments = new ArrayList<>();

    protected LoanJpaEntity() {
    }

    LoanJpaEntity(String loanId) {
        this.loanId = loanId;
    }

    public String getLoanId() { return loanId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public String getCurrency() { return currency; }
    public BigDecimal getAnnualInterestRate() { return annualInterestRate; }
    public String getRateBasis() { return rateBasis; }
    public BigDecimal getLegacyFlatRate() { return legacyFlatRate; }
    public int getTermMonths() { return termMonths; }
    public String getStatus() { return status; }
    public LocalDate getApplicationDate() { return applicationDate; }
    public LocalDate getApprovalDate() { return approvalDate; }
    public LocalDate getDisbursementDate() { return disbursementDate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public BigDecimal getOutstandingBalance() { return outstandingBalance; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public String getLegacyLoanId() { return legacyLoanId; }
    public List<LoanInstallmentJpaEntity> getInstallments() { return installments; }

    void setCustomerId(String customerId) { this.customerId = customerId; }
    void setPrincipalAmount(BigDecimal principalAmount) { this.principalAmount = principalAmount; }
    void setCurrency(String currency) { this.currency = currency; }
    void setAnnualInterestRate(BigDecimal annualInterestRate) { this.annualInterestRate = annualInterestRate; }
    void setRateBasis(String rateBasis) { this.rateBasis = rateBasis; }
    void setTermMonths(int termMonths) { this.termMonths = termMonths; }
    void setStatus(String status) { this.status = status; }
    void setApplicationDate(LocalDate applicationDate) { this.applicationDate = applicationDate; }
    void setApprovalDate(LocalDate approvalDate) { this.approvalDate = approvalDate; }
    void setDisbursementDate(LocalDate disbursementDate) { this.disbursementDate = disbursementDate; }
    void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }
    void setOutstandingBalance(BigDecimal outstandingBalance) { this.outstandingBalance = outstandingBalance; }
    void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
