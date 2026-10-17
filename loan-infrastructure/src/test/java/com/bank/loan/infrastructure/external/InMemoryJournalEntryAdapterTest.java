package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.JournalEntry;
import com.bank.loan.domain.port.out.JournalEntry.Line;
import com.bank.loan.domain.port.out.JournalEntry.Side;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryJournalEntryAdapterTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private final InMemoryJournalEntryAdapter adapter = new InMemoryJournalEntryAdapter(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aBookingGetsAnIdAndTheSameReferenceWithTheSameLinesReplaysIt() {
        JournalEntry entry = entry("LOAN-MEM-1", "1250.00");

        JournalEntryReceipt first = adapter.post(entry);
        JournalEntryReceipt again = adapter.post(entry("LOAN-MEM-1", "1250.00"));

        assertThat(first.journalEntryId()).startsWith("JE-");
        assertThat(first.replay()).isFalse();
        assertThat(first.bookedAt()).isEqualTo(NOW);
        assertThat(again.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(again.replay()).isTrue();
        assertThat(adapter.post(entry("LOAN-MEM-2", "1250.00")).journalEntryId()).isNotEqualTo(first.journalEntryId());
    }

    @Test
    void theSameReferenceWithOtherLinesIsAReusedKey() {
        adapter.post(entry("LOAN-MEM-3", "1250.00"));

        assertThatThrownBy(() -> adapter.post(entry("LOAN-MEM-3", "1250.01")))
            .isInstanceOf(JournalEntryRejectedException.class)
            .satisfies(rejected -> assertThat(((JournalEntryRejectedException) rejected).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED"))
            .message().doesNotContain("LOAN-MEM-3", "ACC-", "1250");
    }

    @Test
    void theDefaultClockIsTheSystemClock() {
        JournalEntryReceipt receipt = new InMemoryJournalEntryAdapter().post(entry("LOAN-MEM-4", "1.00"));
        assertThat(receipt.bookedAt()).isAfter(NOW);
    }

    private static JournalEntry entry(String reference, String amount) {
        return new JournalEntry(reference, AED, null, List.of(
            new Line("ACC-CLEARING-1", Side.DEBIT, Money.of(amount, AED)),
            new Line("ACC-CUSTOMER-1", Side.CREDIT, Money.of(amount, AED))));
    }
}
