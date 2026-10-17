package com.bank.loan.domain.port.out;

import com.bank.loan.domain.port.out.JournalEntry.Line;
import com.bank.loan.domain.port.out.JournalEntry.Side;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariants of a journal entry before it is sent to the accounts ledger (ADR-030). */
class JournalEntryTest {

    private static final Currency AED = Currency.getInstance("AED");

    @Test
    void aBalancedEntryKeepsItsLinesInOrderAndCannotBeChanged() {
        JournalEntry entry = new JournalEntry("LOAN-JE-1", AED, "disbursement LOAN-JE-1", List.of(
            new Line("ACC-CLEARING", Side.DEBIT, aed("1250.00")),
            new Line("ACC-CUSTOMER", Side.CREDIT, aed("1250.00"))));

        assertThat(entry.reference()).isEqualTo("LOAN-JE-1");
        assertThat(entry.currency()).isEqualTo(AED);
        assertThat(entry.narrative()).isEqualTo("disbursement LOAN-JE-1");
        assertThat(entry.lines()).extracting(Line::accountId).containsExactly("ACC-CLEARING", "ACC-CUSTOMER");
        assertThatThrownBy(() -> entry.lines().add(new Line("ACC-X", Side.DEBIT, aed("1.00"))))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theNarrativeIsOptionalAndSeveralLinesMayBalanceOneSide() {
        JournalEntry entry = new JournalEntry("LOAN-JE-2", AED, null, List.of(
            new Line("ACC-CLEARING", Side.DEBIT, aed("1000.00")),
            new Line("ACC-A", Side.CREDIT, aed("600.00")),
            new Line("ACC-B", Side.CREDIT, aed("400.00"))));

        assertThat(entry.narrative()).isNull();
        assertThat(entry.lines()).hasSize(3);
    }

    @Test
    void debitsMustEqualCredits() {
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-3", AED, null, List.of(
                new Line("ACC-CLEARING", Side.DEBIT, aed("1250.00")),
                new Line("ACC-CUSTOMER", Side.CREDIT, aed("1249.99")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AED 1250.00")
            .hasMessageContaining("AED 1249.99")
            .message().doesNotContain("ACC-CLEARING", "ACC-CUSTOMER");
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-3", AED, null, List.of(
                new Line("ACC-CLEARING", Side.DEBIT, aed("10.00")),
                new Line("ACC-CUSTOMER", Side.DEBIT, aed("10.00")))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyLineIsInTheEntryCurrency() {
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-4", AED, null, List.of(
                new Line("ACC-CLEARING", Side.DEBIT, aed("10.00")),
                new Line("ACC-CUSTOMER", Side.CREDIT, Money.usd(new BigDecimal("10.00"))))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("AED");
    }

    @Test
    void anEntryNeedsAReferenceAndAtLeastTwoLines() {
        List<Line> balanced = List.of(
            new Line("ACC-CLEARING", Side.DEBIT, aed("10.00")),
            new Line("ACC-CUSTOMER", Side.CREDIT, aed("10.00")));

        assertThatThrownBy(() -> new JournalEntry(" ", AED, null, balanced)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalEntry(null, AED, null, balanced)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-5", null, null, balanced)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-5", AED, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JournalEntry("LOAN-JE-5", AED, null, List.of(balanced.get(0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("two lines");
    }

    @Test
    void aLineNamesAnAccountASideAndAPositiveAmount() {
        assertThatThrownBy(() -> new Line(" ", Side.DEBIT, aed("10.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Line(null, Side.DEBIT, aed("10.00"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Line("ACC-1", null, aed("10.00"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Line("ACC-1", Side.DEBIT, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Line("ACC-1", Side.DEBIT, aed("0.00")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> new Line("ACC-1", Side.CREDIT, aed("-1.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReceiptNamesTheBookingAndWhetherItWasAReplay() {
        Instant bookedAt = Instant.parse("2026-10-17T09:00:00Z");
        JournalEntryReceipt receipt = new JournalEntryReceipt("JE-1", true, bookedAt);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-1");
        assertThat(receipt.replay()).isTrue();
        assertThat(receipt.bookedAt()).isEqualTo(bookedAt);
        assertThatThrownBy(() -> new JournalEntryReceipt(" ", false, bookedAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalEntryReceipt(null, false, bookedAt)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JournalEntryReceipt("JE-1", false, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void failuresCarryTheLedgerCodeAndTheCause() {
        RuntimeException cause = new RuntimeException("422");
        JournalEntryRejectedException rejected = new JournalEntryRejectedException("INSUFFICIENT_FUNDS", "refused", cause);
        assertThat(rejected.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected).hasMessage("refused").hasCause(cause);
        assertThat(new JournalEntryRejectedException("UNBALANCED_ENTRY", "refused").getCause()).isNull();

        assertThat(new AccountsLedgerUnavailableException("down", cause)).hasMessage("down").hasCause(cause);
        assertThat(new AccountsLedgerUnavailableException("down")).hasMessage("down");
    }

    private static Money aed(String amount) {
        return Money.of(amount, AED);
    }
}
