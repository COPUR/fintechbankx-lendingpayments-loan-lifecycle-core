package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.JournalEntry;
import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory journal for local runs and tests, keyed like the accounts ledger:
 * the same reference with the same lines replays the first booking, with
 * other lines it is IDEMPOTENCY_KEY_REUSED. It keeps no balances, so nothing
 * is ever INSUFFICIENT_FUNDS or ACCOUNT_NOT_OPERATIONAL here.
 *
 * Enabled only with {@code journal.adapter=in-memory}. Every deployed
 * environment uses {@link AccountsLedgerHttpAdapter}.
 */
@Component
@ConditionalOnProperty(name = "journal.adapter", havingValue = "in-memory")
public class InMemoryJournalEntryAdapter implements JournalEntryPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryJournalEntryAdapter.class);

    private final Clock clock;
    private final Map<String, Booking> journal = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryJournalEntryAdapter() {
        this(Clock.systemUTC());
    }

    public InMemoryJournalEntryAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized JournalEntryReceipt post(JournalEntry entry) {
        Booking booked = journal.get(entry.reference());
        if (booked != null) {
            if (!booked.entry().equals(entry)) {
                throw new JournalEntryRejectedException("IDEMPOTENCY_KEY_REUSED",
                    "Journal answered 409 IDEMPOTENCY_KEY_REUSED to the journal entry; not retried");
            }
            return new JournalEntryReceipt(booked.receipt().journalEntryId(), true, booked.receipt().bookedAt());
        }
        JournalEntryReceipt receipt = new JournalEntryReceipt("JE-" + UUID.randomUUID(), false, clock.instant());
        journal.put(entry.reference(), new Booking(entry, receipt));
        log.debug("Journal entry {} booked", receipt.journalEntryId());
        return receipt;
    }

    private record Booking(JournalEntry entry, JournalEntryReceipt receipt) {
    }
}
