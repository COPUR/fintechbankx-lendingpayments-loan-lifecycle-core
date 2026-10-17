package com.bank.loan.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * outstanding_balance has one meaning for every loan: the total still due on
 * the schedule (principal plus scheduled interest). New loans get an
 * amortised schedule; migrated loans keep the monolith's flat schedule.
 * Figures below were worked out by hand with HALF_UP to 2 decimals.
 */
class LoanRepaymentScheduleTest {

    private static final LoanId ID = LoanId.of("LOAN-SCH-1");
    private static final CustomerId CUSTOMER = CustomerId.of("CUST-SCH-1");

    @Test
    void newLoanGetsAnAmortisedScheduleWhoseLastInstallmentAbsorbsRounding() {
        // 12,000.00 at 6% nominal annual over 12 months: r = 0.005, PMT = 1,032.80
        Loan loan = Loan.create(ID, CUSTOMER, aed("12000.00"), InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12));

        List<LoanInstallment> schedule = loan.getInstallments();
        assertThat(schedule).hasSize(12);
        assertThat(schedule.getFirst().getAmount()).isEqualTo(aed("1032.80"));
        assertThat(schedule.getFirst().getInterestComponent()).isEqualTo(aed("60.00"));
        assertThat(schedule.getFirst().getPrincipalComponent()).isEqualTo(aed("972.80"));
        assertThat(schedule.get(10).getAmount()).isEqualTo(aed("1032.80"));
        assertThat(schedule.getLast().getAmount()).isEqualTo(aed("1032.78"));
        assertThat(schedule.getLast().getPrincipalComponent()).isEqualTo(aed("1027.64"));
        assertThat(schedule.getLast().getInterestComponent()).isEqualTo(aed("5.14"));
        assertThat(schedule.stream().map(LoanInstallment::getPrincipalComponent).reduce(Money::add).orElseThrow())
            .isEqualTo(aed("12000.00"));
        assertThat(loan.getTotalInstallmentAmount()).isEqualTo(aed("12393.58"));
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("12393.58"));
        assertThat(loan.calculateMonthlyPayment()).isEqualTo(aed("1032.80"));
        assertThat(loan.getRateBasis()).isEqualTo(RateBasis.NOMINAL_ANNUAL);
        assertThat(loan.getFlatTotalRate()).isEmpty();
    }

    @Test
    void flatTotalRateIsRecoveredExactlyFromTheAnnualisedPercentForEveryMonolithTerm() {
        // monolith: interest_rate 0.200 over 9 months; stored here as 0.200 * 100 * 12 / 9 = 26.6667 (4 decimals)
        Loan loan = Loan.rehydrate(ID, CUSTOMER, aed("9000.00"), InterestRate.of(new BigDecimal("26.6667")),
            RateBasis.FLAT_TOTAL, LoanTerm.ofMonths(9), LoanStatus.DISBURSED, LocalDate.of(2025, 6, 1),
            LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 3, 1), aed("10800.00"),
            List.of(), LocalDateTime.of(2025, 6, 1, 9, 30), LocalDateTime.of(2025, 6, 1, 9, 30), 0L);

        assertThat(loan.getFlatTotalRate()).contains(new BigDecimal("0.200"));
    }

    @Test
    void zeroInterestScheduleSplitsPrincipalAndPutsTheRemainderOnTheLastInstallment() {
        Loan loan = Loan.create(ID, CUSTOMER, aed("12000.00"), InterestRate.zero(), LoanTerm.ofMonths(7));

        assertThat(loan.getInstallments()).extracting(LoanInstallment::getAmount)
            .containsExactly(aed("1714.29"), aed("1714.29"), aed("1714.29"), aed("1714.29"),
                aed("1714.29"), aed("1714.29"), aed("1714.26"));
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("12000.00"));
    }

    @Test
    void paymentIsAllocatedInInstallmentOrderInterestFirst() {
        Loan loan = disbursed(Loan.create(ID, CUSTOMER, aed("12000.00"), InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12)));

        PaymentResult result = loan.makePayment(PaymentId.of("PAY-1"), aed("1100.00"));

        // installment 1 in full, then 67.20 on installment 2 whose interest is 11,027.20 x 0.005 = 55.14
        assertThat(result.getPaymentId()).isEqualTo(PaymentId.of("PAY-1"));
        assertThat(result.getAllocations()).containsExactly(
            new InstallmentAllocation(1, aed("1032.80"), aed("972.80"), aed("60.00")),
            new InstallmentAllocation(2, aed("67.20"), aed("12.06"), aed("55.14")));
        assertThat(result.getPaymentDistribution().getPrincipalPayment()).isEqualTo(aed("984.86"));
        assertThat(result.getPaymentDistribution().getInterestPayment()).isEqualTo(aed("115.14"));
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("11293.58"));
        assertThat(loan.getInstallments().get(0).getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(loan.getInstallments().get(1).getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);

        LoanPaymentMadeEvent made = (LoanPaymentMadeEvent) loan.getDomainEvents().getLast();
        assertThat(made.getPaymentId()).isEqualTo(PaymentId.of("PAY-1"));
        assertThat(made.getPreviousBalance()).isEqualTo(aed("12393.58"));
        assertThat(made.getNewBalance()).isEqualTo(aed("11293.58"));
    }

    @Test
    void secondPaymentContinuesOnThePartlyPaidInstallmentWithItsRemainingInterestFirst() {
        Loan loan = disbursed(Loan.create(ID, CUSTOMER, aed("12000.00"), InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12)));
        loan.makePayment(PaymentId.of("PAY-1"), aed("1050.00"));   // 17.20 of installment 2: all interest

        PaymentResult second = loan.makePayment(PaymentId.of("PAY-2"), aed("100.00"));

        // installment 2 interest 55.14: 17.20 already paid, 37.94 left, then 62.06 principal
        assertThat(second.getAllocations()).containsExactly(new InstallmentAllocation(2, aed("100.00"), aed("62.06"), aed("37.94")));
    }

    @Test
    void payingTheWholeScheduleMakesTheLoanFullyPaidFromTheSchedule() {
        Loan loan = disbursed(Loan.create(ID, CUSTOMER, aed("1000.00"), InterestRate.zero(), LoanTerm.ofMonths(3)));

        PaymentResult result = loan.makePayment(PaymentId.of("PAY-ALL"), aed("1000.00"));

        assertThat(result.getAllocations()).extracting(InstallmentAllocation::amount)
            .containsExactly(aed("333.33"), aed("333.33"), aed("333.34"));
        assertThat(loan.isFullyPaid()).isTrue();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.FULLY_PAID);
        assertThat(result.isLoanFullyPaid()).isTrue();
        assertThat(loan.getDomainEvents()).last().isInstanceOf(LoanFullyPaidEvent.class);
    }

    @Test
    void paymentAboveTheScheduledTotalIsRejected() {
        Loan loan = disbursed(Loan.create(ID, CUSTOMER, aed("1000.00"), InterestRate.zero(), LoanTerm.ofMonths(3)));

        assertThatThrownBy(() -> loan.makePayment(PaymentId.of("PAY-X"), aed("1000.01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot exceed outstanding balance");
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("1000.00"));
    }

    @Test
    void paymentInAnotherCurrencyIsRejected() {
        Loan loan = disbursed(Loan.create(ID, CUSTOMER, aed("1000.00"), InterestRate.zero(), LoanTerm.ofMonths(3)));

        assertThatThrownBy(() -> loan.makePayment(PaymentId.of("PAY-USD"), Money.usd(new BigDecimal("10.00"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void migratedFlatRateLoanReportsItsPersistedInstallmentAsMonthlyPayment() {
        // Monolith loan: 12,000.00, flat 20% over 12 months -> 12 x 1,200.00, 3 paid
        List<LoanInstallment> schedule = new java.util.ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            boolean paid = n <= 3;
            schedule.add(LoanInstallment.rehydrate(ID, CUSTOMER, n, aed("1200.00"), aed("1000.00"), aed("200.00"),
                LocalDate.of(2025, 7, 1).plusMonths(n - 1), paid ? aed("1200.00") : aed("0.00"),
                paid ? LocalDateTime.of(2025, 6, 28, 0, 0).plusMonths(n - 1) : null,
                paid ? InstallmentStatus.PAID : InstallmentStatus.PENDING));
        }
        Loan loan = Loan.rehydrate(ID, CUSTOMER, aed("12000.00"), InterestRate.of(new BigDecimal("20.0")),
            RateBasis.FLAT_TOTAL, LoanTerm.ofMonths(12), LoanStatus.DISBURSED, LocalDate.of(2025, 6, 1),
            LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1), aed("10800.00"),
            schedule, LocalDateTime.of(2025, 6, 1, 9, 30), LocalDateTime.of(2025, 9, 1, 0, 0), 0L);

        assertThat(loan.getRateBasis()).isEqualTo(RateBasis.FLAT_TOTAL);
        assertThat(loan.getFlatTotalRate()).contains(new BigDecimal("0.200"));
        assertThat(loan.calculateMonthlyPayment()).isEqualTo(aed("1200.00"));
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("10800.00"));

        PaymentResult result = loan.makePayment(PaymentId.of("PAY-M"), aed("1200.00"));

        assertThat(result.getAllocations()).containsExactly(new InstallmentAllocation(4, aed("1200.00"), aed("1000.00"), aed("200.00")));
        assertThat(loan.getOutstandingBalance()).isEqualTo(aed("9600.00"));
    }

    @Test
    void scheduleRulesAreCheckedInTheLoanCurrency() {
        Loan usdLoan = Loan.createWithInstallments(ID, CUSTOMER, Money.usd(new BigDecimal("12000.00")),
            InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12));

        assertThat(usdLoan.getOutstandingBalance()).isEqualTo(Money.usd(new BigDecimal("12393.58")));
    }

    private static Loan disbursed(Loan loan) {
        loan.approve();
        loan.disburse();
        loan.clearDomainEvents();
        return loan;
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
