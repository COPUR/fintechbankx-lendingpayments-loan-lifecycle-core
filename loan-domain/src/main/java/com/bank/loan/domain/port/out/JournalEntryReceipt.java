package com.bank.loan.domain.port.out;

import java.time.Instant;
import java.util.Objects;

/** The ledger's answer to a booked entry: its id, whether it was already booked under the reference, and when. */
public record JournalEntryReceipt(String journalEntryId, boolean replay, Instant bookedAt) {

    public JournalEntryReceipt {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(bookedAt, "bookedAt");
        if (journalEntryId.isBlank()) {
            throw new IllegalArgumentException("A journal entry receipt needs the ledger's entry id");
        }
    }
}
