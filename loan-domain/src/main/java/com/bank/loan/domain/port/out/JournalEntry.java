package com.bank.loan.domain.port.out;

import com.bank.shared.kernel.domain.Money;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * One double entry for the accounts ledger: a business reference (the
 * idempotency key), one currency, an optional narrative and at least two
 * lines whose debits equal their credits.
 */
public record JournalEntry(String reference, Currency currency, String narrative, List<Line> lines) {

    public enum Side { DEBIT, CREDIT }

    /** One posting: an account, the side it is posted on and a positive amount in the entry currency. */
    public record Line(String accountId, Side side, Money amount) {
        public Line {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(amount, "amount");
            if (accountId.isBlank()) {
                throw new IllegalArgumentException("A journal line needs an account id");
            }
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("A journal line amount must be positive, was " + amount);
            }
        }
    }

    public JournalEntry {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(lines, "lines");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("A journal entry needs a reference");
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two lines, had " + lines.size());
        }
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (Line line : lines) {
            if (!line.amount().getCurrency().equals(currency)) {
                throw new IllegalArgumentException("A journal line is in " + line.amount().getCurrency()
                    + " but the entry is in " + currency);
            }
            if (line.side() == Side.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new IllegalArgumentException("Debits " + debits + " do not equal credits " + credits);
        }
        lines = List.copyOf(lines);
    }
}
