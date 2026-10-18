package com.bank.loan.domain.port.out;

import com.bank.loan.domain.LoanId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepaymentBookingTest {

    private static final LoanId LOAN = LoanId.of("LOAN-BK-1");

    @Test
    void principalReducesTheReceivableInterestIsIncomeAndTheWholeAmountComesOffClearing() {
        RepaymentBooking booking = new RepaymentBooking("loan-repayment:PAY-1", LOAN, aed("1000.00"), aed("200.00"),
            "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", "INTEREST-INCOME");

        JournalEntry entry = booking.toJournalEntry();

        assertThat(entry.reference()).isEqualTo("loan-repayment:PAY-1");
        assertThat(entry.lines()).containsExactly(
            new JournalEntry.Line("DISBURSEMENT-CLEARING", JournalEntry.Side.DEBIT, aed("1200.00")),
            new JournalEntry.Line("LOAN-RECEIVABLE", JournalEntry.Side.CREDIT, aed("1000.00")),
            new JournalEntry.Line("INTEREST-INCOME", JournalEntry.Side.CREDIT, aed("200.00")));
    }

    @Test
    void aPrincipalOnlyRepaymentHasNoIncomeLine() {
        RepaymentBooking booking = new RepaymentBooking("loan-repayment:PAY-2", LOAN, aed("100.00"), aed("0.00"),
            "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null);

        assertThat(booking.toJournalEntry().lines()).extracting(JournalEntry.Line::accountId)
            .containsExactly("DISBURSEMENT-CLEARING", "LOAN-RECEIVABLE");
    }

    @Test
    void anInterestOnlyRepaymentLeavesTheReceivableUntouched() {
        RepaymentBooking booking = new RepaymentBooking("loan-repayment:PAY-3", LOAN, aed("0.00"), aed("200.00"),
            "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", "INTEREST-INCOME");

        assertThat(booking.toJournalEntry().lines()).extracting(JournalEntry.Line::accountId)
            .containsExactly("DISBURSEMENT-CLEARING", "INTEREST-INCOME");
    }

    @Test
    void interestWithoutAnIncomeAccountIsRefused() {
        assertThatThrownBy(() -> new RepaymentBooking("loan-repayment:PAY-4", LOAN, aed("1000.00"), aed("200.00"),
                "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("income account");
    }

    @Test
    void aBookingNeedsAReferencePositiveAmountsAndOneCurrency() {
        assertThatThrownBy(() -> new RepaymentBooking(" ", LOAN, aed("100.00"), aed("0.00"),
                "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RepaymentBooking("loan-repayment:PAY-5", LOAN, aed("0.00"), aed("0.00"),
                "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive amount");
        assertThatThrownBy(() -> new RepaymentBooking("loan-repayment:PAY-6", LOAN, aed("100.00"),
                Money.usd(new BigDecimal("10.00")), "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", "INTEREST-INCOME"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void aNegativeAmountIsRefused() {
        assertThatThrownBy(() -> new RepaymentBooking("loan-repayment:PAY-7", LOAN, aed("-1.00"), aed("0.00"),
                "LOAN-RECEIVABLE", "DISBURSEMENT-CLEARING", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
