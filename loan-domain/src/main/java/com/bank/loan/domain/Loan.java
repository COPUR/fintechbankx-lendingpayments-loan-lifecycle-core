package com.bank.loan.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.AggregateRoot;
import com.bank.shared.kernel.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loan Aggregate Root
 * 
 * Represents a financial loan with its terms, payment schedule,
 * and business rules for loan lifecycle management.
 */
public class Loan extends AggregateRoot<LoanId> {
    
    private static final BigDecimal MIN_LOAN_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal MAX_LOAN_AMOUNT = new BigDecimal("500000.00");
    private static final int MIN_INSTALLMENTS = 6;
    private static final int MAX_INSTALLMENTS = 60;
    
    private LoanId loanId;
    private CustomerId customerId;
    private Money principalAmount;
    private InterestRate interestRate;
    private RateBasis rateBasis;
    private LoanTerm loanTerm;
    private LoanStatus status;
    private LocalDate applicationDate;
    private LocalDate approvalDate;
    private LocalDate disbursementDate;
    private LocalDate maturityDate;
    /** Only used for loans without a schedule (none are created any more). */
    private Money storedOutstandingBalance;
    private List<LoanInstallment> installments;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    protected Loan() {
        this.installments = new ArrayList<>();
    }
    
    private Loan(LoanId loanId, CustomerId customerId, Money principalAmount,
                InterestRate interestRate, LoanTerm loanTerm) {
        this();
        this.loanId = Objects.requireNonNull(loanId, "Loan ID cannot be null");
        this.customerId = Objects.requireNonNull(customerId, "Customer ID cannot be null");
        this.principalAmount = Objects.requireNonNull(principalAmount, "Principal amount cannot be null");
        this.interestRate = Objects.requireNonNull(interestRate, "Interest rate cannot be null");
        this.rateBasis = RateBasis.NOMINAL_ANNUAL;
        this.loanTerm = Objects.requireNonNull(loanTerm, "Loan term cannot be null");
        this.status = LoanStatus.CREATED;
        this.applicationDate = LocalDate.now();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        
        validateLoanData();
        generateInstallments();
        this.storedOutstandingBalance = getRemainingInstallmentAmount();
        
        addDomainEvent(new LoanCreatedEvent(loanId, customerId, principalAmount));
    }
    
    /**
     * New loan with its amortised repayment schedule. The outstanding balance
     * is the total of the schedule: principal plus scheduled interest.
     */
    public static Loan create(LoanId loanId, CustomerId customerId, Money principalAmount,
                             InterestRate interestRate, LoanTerm loanTerm) {
        return new Loan(loanId, customerId, principalAmount, interestRate, loanTerm);
    }
    
    /**
     * Like {@link #create} but also enforces the retail product bounds
     * (1,000 to 500,000 in the loan currency, 6 to 60 months).
     */
    public static Loan createWithInstallments(LoanId loanId, CustomerId customerId, Money principalAmount,
                                            InterestRate interestRate, LoanTerm loanTerm) {
        validateProductBounds(principalAmount, loanTerm);
        return new Loan(loanId, customerId, principalAmount, interestRate, loanTerm);
    }
    
    /**
     * Rebuilds a loan created by this service (nominal annual rate).
     */
    public static Loan rehydrate(LoanId loanId, CustomerId customerId, Money principalAmount,
                                 InterestRate interestRate, LoanTerm loanTerm, LoanStatus status,
                                 LocalDate applicationDate, LocalDate approvalDate,
                                 LocalDate disbursementDate, LocalDate maturityDate,
                                 Money outstandingBalance, List<LoanInstallment> installments,
                                 LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        return rehydrate(loanId, customerId, principalAmount, interestRate, RateBasis.NOMINAL_ANNUAL, loanTerm,
            status, applicationDate, approvalDate, disbursementDate, maturityDate, outstandingBalance,
            installments, createdAt, updatedAt, version);
    }

    /**
     * Rebuilds a loan from persisted state. Used by persistence adapters only:
     * it registers no domain events and skips creation-time validation so that
     * historical loans migrated from the monolith load as they were stored.
     * When the loan has a schedule its outstanding balance is derived from it;
     * outstandingBalance is used only for a loan without installments.
     */
    public static Loan rehydrate(LoanId loanId, CustomerId customerId, Money principalAmount,
                                 InterestRate interestRate, RateBasis rateBasis, LoanTerm loanTerm,
                                 LoanStatus status, LocalDate applicationDate, LocalDate approvalDate,
                                 LocalDate disbursementDate, LocalDate maturityDate,
                                 Money outstandingBalance, List<LoanInstallment> installments,
                                 LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        Loan loan = new Loan();
        loan.loanId = Objects.requireNonNull(loanId, "Loan ID cannot be null");
        loan.customerId = Objects.requireNonNull(customerId, "Customer ID cannot be null");
        loan.principalAmount = Objects.requireNonNull(principalAmount, "Principal amount cannot be null");
        loan.interestRate = Objects.requireNonNull(interestRate, "Interest rate cannot be null");
        loan.rateBasis = Objects.requireNonNull(rateBasis, "Rate basis cannot be null");
        loan.loanTerm = Objects.requireNonNull(loanTerm, "Loan term cannot be null");
        loan.status = Objects.requireNonNull(status, "Status cannot be null");
        loan.applicationDate = applicationDate;
        loan.approvalDate = approvalDate;
        loan.disbursementDate = disbursementDate;
        loan.maturityDate = maturityDate;
        loan.storedOutstandingBalance = Objects.requireNonNull(outstandingBalance, "Outstanding balance cannot be null");
        if (installments != null) {
            loan.installments.addAll(installments.stream()
                .sorted(java.util.Comparator.comparingInt(LoanInstallment::getInstallmentNumber))
                .toList());
        }
        loan.createdAt = createdAt;
        loan.updatedAt = updatedAt;
        loan.setVersion(version);
        return loan;
    }

    private void validateLoanData() {
        if (principalAmount.isNegative() || principalAmount.isZero()) {
            throw new IllegalArgumentException("Principal amount must be positive");
        }
        if (interestRate.isNegative()) {
            throw new IllegalArgumentException("Interest rate cannot be negative");
        }
        if (loanTerm.getMonths() <= 0) {
            throw new IllegalArgumentException("Loan term must be positive");
        }
    }
    
    private static void validateProductBounds(Money principalAmount, LoanTerm loanTerm) {
        Money min = Money.of(MIN_LOAN_AMOUNT, principalAmount.getCurrency());
        Money max = Money.of(MAX_LOAN_AMOUNT, principalAmount.getCurrency());
        if (principalAmount.compareTo(min) < 0) {
            throw new IllegalArgumentException(
                String.format("Loan amount must be at least %s", min));
        }
        if (principalAmount.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                String.format("Loan amount cannot exceed %s", max));
        }
        if (loanTerm.getMonths() < MIN_INSTALLMENTS) {
            throw new IllegalArgumentException(
                String.format("Loan term must be at least %d months", MIN_INSTALLMENTS));
        }
        if (loanTerm.getMonths() > MAX_INSTALLMENTS) {
            throw new IllegalArgumentException(
                String.format("Loan term cannot exceed %d months", MAX_INSTALLMENTS));
        }
    }
    
    @Override
    public LoanId getId() {
        return loanId;
    }
    
    public CustomerId getCustomerId() {
        return customerId;
    }
    
    public Money getPrincipalAmount() {
        return principalAmount;
    }
    
    public InterestRate getInterestRate() {
        return interestRate;
    }

    public RateBasis getRateBasis() {
        return rateBasis;
    }

    /**
     * For loans migrated from the monolith (FLAT_TOTAL): the monolith's
     * interest_rate as it stored it, a fraction of the principal charged once
     * over the whole term (0.200 = 20%), recovered from the annualised
     * percent (annual * months / 1200, 3 decimals like the monolith column).
     * Empty for loans created here (nominal annual rate).
     */
    public java.util.Optional<BigDecimal> getFlatTotalRate() {
        if (rateBasis != RateBasis.FLAT_TOTAL) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(interestRate.getAnnualRate()
            .multiply(BigDecimal.valueOf(loanTerm.getMonths()))
            .divide(BigDecimal.valueOf(1200), 3, java.math.RoundingMode.HALF_UP));
    }
    
    public LoanTerm getLoanTerm() {
        return loanTerm;
    }
    
    public LoanStatus getStatus() {
        return status;
    }
    
    public LocalDate getApplicationDate() {
        return applicationDate;
    }
    
    public LocalDate getApprovalDate() {
        return approvalDate;
    }
    
    public LocalDate getDisbursementDate() {
        return disbursementDate;
    }
    
    public LocalDate getMaturityDate() {
        return maturityDate;
    }
    
    /**
     * Total amount still due: the unpaid part of every installment
     * (principal plus scheduled interest).
     */
    public Money getOutstandingBalance() {
        return installments.isEmpty() ? storedOutstandingBalance : getRemainingInstallmentAmount();
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void approve() {
        if (!status.canBeApproved()) {
            throw new IllegalStateException("Loan cannot be approved in current status: " + status);
        }
        this.status = LoanStatus.APPROVED;
        this.approvalDate = LocalDate.now();
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new LoanApprovedEvent(loanId, customerId, principalAmount));
    }
    
    public void reject(String reason) {
        if (!status.canBeRejected()) {
            throw new IllegalStateException("Loan cannot be rejected in current status: " + status);
        }
        this.status = LoanStatus.REJECTED;
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new LoanRejectedEvent(loanId, customerId, reason));
    }
    
    /**
     * Fails unless the loan may be disbursed now. Lets the application check
     * the rule before it reserves credit with the customer context.
     */
    public void ensureCanBeDisbursed() {
        if (!status.canBeDisbursed()) {
            throw new IllegalStateException("Loan cannot be disbursed in current status: " + status);
        }
    }

    public void disburse() {
        ensureCanBeDisbursed();
        this.status = LoanStatus.DISBURSED;
        this.disbursementDate = LocalDate.now();
        this.maturityDate = calculateMaturityDate();
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new LoanDisbursedEvent(loanId, customerId, principalAmount, disbursementDate));
    }
    
    public void cancel(String reason) {
        if (!status.canBeCancelled()) {
            throw new IllegalStateException("Loan cannot be cancelled in current status: " + status);
        }
        this.status = LoanStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new LoanCancelledEvent(loanId, customerId, reason));
    }
    
    /**
     * Applies a repayment under a new payment id.
     */
    public PaymentResult makePayment(Money paymentAmount) {
        return makePayment(PaymentId.generate(), paymentAmount);
    }

    /**
     * Applies a repayment to the schedule: installments in order, interest
     * before principal within each installment. The loan is FULLY_PAID once
     * every installment is paid.
     *
     * @param paymentId id of the repayment (the payment service's id when it
     *                  comes from a completed loan payment)
     */
    public PaymentResult makePayment(PaymentId paymentId, Money paymentAmount) {
        Objects.requireNonNull(paymentId, "Payment ID cannot be null");
        validatePaymentPreconditions(paymentAmount);

        Money previousBalance = getOutstandingBalance();
        List<InstallmentAllocation> allocations = installments.isEmpty()
            ? List.of()
            : allocateAcrossInstallments(paymentAmount);
        if (installments.isEmpty()) {
            storedOutstandingBalance = storedOutstandingBalance.subtract(paymentAmount);
        }
        Money zero = Money.zero(paymentAmount.getCurrency());
        Money interest = allocations.stream().map(InstallmentAllocation::interest).reduce(zero, Money::add);

        PaymentDistribution distribution = PaymentDistribution.builder()
            .totalPayment(paymentAmount)
            .principalPayment(paymentAmount.subtract(interest))
            .interestPayment(interest)
            .feePayment(zero)
            .previousBalance(previousBalance)
            .paymentDate(LocalDate.now())
            .build();
        distribution.validate();

        this.updatedAt = LocalDateTime.now();
        boolean fullyPaid = installments.isEmpty() ? getOutstandingBalance().isZero() : isFullyPaid();
        if (fullyPaid) {
            this.status = LoanStatus.FULLY_PAID;
        }

        addDomainEvent(new LoanPaymentMadeEvent(loanId, customerId, paymentId, paymentAmount,
            previousBalance, getOutstandingBalance()));
        if (fullyPaid) {
            addDomainEvent(new LoanFullyPaidEvent(loanId, customerId));
        }

        PaymentResult result = PaymentResult.builder()
            .loanId(loanId)
            .paymentId(paymentId)
            .paymentDistribution(distribution)
            .allocations(List.copyOf(allocations))
            .newOutstandingBalance(getOutstandingBalance())
            .loanStatus(status)
            .paymentProcessedAt(LocalDateTime.now())
            .success(true)
            .build();
        result.validate();
        return result;
    }

    private List<InstallmentAllocation> allocateAcrossInstallments(Money paymentAmount) {
        List<InstallmentAllocation> allocations = new ArrayList<>();
        Money remaining = paymentAmount;
        for (LoanInstallment installment : installments) {
            if (remaining.isZero()) {
                break;
            }
            if (installment.isPaid() || installment.getRemainingAmount().isZero()) {
                continue;
            }
            InstallmentAllocation allocation = installment.allocate(remaining);
            allocations.add(allocation);
            remaining = remaining.subtract(allocation.amount());
        }
        return allocations;
    }

    private void validatePaymentPreconditions(Money paymentAmount) {
        if (!status.canAcceptPayments()) {
            throw new IllegalStateException(
                String.format("Loan cannot accept payments in current status: %s", status));
        }
        if (paymentAmount == null) {
            throw new IllegalArgumentException("Payment amount cannot be null");
        }
        if (!paymentAmount.getCurrency().equals(principalAmount.getCurrency())) {
            throw new IllegalArgumentException(String.format(
                "Payment currency %s does not match the loan currency %s",
                paymentAmount.getCurrency(), principalAmount.getCurrency()));
        }
        if (paymentAmount.isNegative() || paymentAmount.isZero()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        Money outstanding = getOutstandingBalance();
        if (paymentAmount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(String.format(
                "Payment amount (%s) cannot exceed outstanding balance (%s)", paymentAmount, outstanding));
        }
    }
    
    private LocalDate calculateMaturityDate() {
        return disbursementDate.plusMonths(loanTerm.getMonths());
    }
    
    public boolean isOverdue() {
        return maturityDate != null && LocalDate.now().isAfter(maturityDate) && !getOutstandingBalance().isZero();
    }
    
    /**
     * The regular installment: the first installment of the persisted
     * schedule (for migrated flat-rate loans, the monolith's installment).
     * Only a loan without a schedule falls back to the annuity formula.
     */
    public Money calculateMonthlyPayment() {
        if (!installments.isEmpty()) {
            return installments.getFirst().getAmount();
        }
        return annuityPayment();
    }

    private Money annuityPayment() {
        int months = loanTerm.getMonths();
        BigDecimal monthlyRate = interestRate.getMonthlyRate();
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principalAmount.divide(BigDecimal.valueOf(months));
        }
        // PMT formula: P * [r(1+r)^n] / [(1+r)^n - 1], rounded HALF_UP to the currency scale
        BigDecimal onePlusRateToN = BigDecimal.ONE.add(monthlyRate).pow(months);
        BigDecimal numerator = monthlyRate.multiply(onePlusRateToN);
        BigDecimal denominator = onePlusRateToN.subtract(BigDecimal.ONE);
        BigDecimal paymentFactor = numerator.divide(denominator, 10, RoundingMode.HALF_UP);
        return principalAmount.multiply(paymentFactor);
    }
    
    public List<LoanInstallment> getInstallments() {
        return new ArrayList<>(installments);
    }
    
    public Money getTotalInstallmentAmount() {
        return installments.stream()
            .map(LoanInstallment::getAmount)
            .reduce(Money.zero(principalAmount.getCurrency()), Money::add);
    }
    
    public Money getTotalInterest() {
        return getTotalInstallmentAmount().subtract(principalAmount);
    }
    
    public Money getRemainingInstallmentAmount() {
        return installments.stream()
            .filter(installment -> !installment.isPaid())
            .map(LoanInstallment::getRemainingAmount)
            .reduce(Money.zero(principalAmount.getCurrency()), Money::add);
    }
    
    public int getRemainingInstallments() {
        return (int) installments.stream()
            .filter(installment -> !installment.isPaid())
            .count();
    }
    
    public boolean isFullyPaid() {
        return !installments.isEmpty() && installments.stream().allMatch(LoanInstallment::isPaid);
    }
    
    public List<LoanInstallment> getOverdueInstallments() {
        LocalDate today = LocalDate.now();
        return installments.stream()
            .filter(installment -> !installment.isPaid() && installment.getDueDate().isBefore(today))
            .toList();
    }
    
    /**
     * Amortised schedule on the declining balance: interest of each month is
     * balance x r rounded HALF_UP to the currency scale, the installment is the
     * rounded annuity payment, and the last installment takes whatever
     * principal is left so the principal components sum exactly to the loan.
     */
    private void generateInstallments() {
        int months = loanTerm.getMonths();
        BigDecimal monthlyRate = interestRate.getMonthlyRate();
        Money payment = annuityPayment();
        Money balance = principalAmount;
        LocalDate dueDate = applicationDate.plusMonths(1);
        for (int n = 1; n <= months; n++) {
            Money interest = balance.multiply(monthlyRate);
            Money principal = n < months ? payment.subtract(interest) : balance;
            if (principal.isNegative()) {
                throw new IllegalArgumentException("Installment does not cover the interest of month " + n);
            }
            installments.add(LoanInstallment.create(loanId, customerId, n, principal, interest, dueDate));
            balance = balance.subtract(principal);
            dueDate = dueDate.plusMonths(1);
        }
    }
}
