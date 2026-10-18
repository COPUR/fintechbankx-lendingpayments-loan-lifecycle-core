package com.bank.loan.domain.port.out;

import com.bank.loan.domain.LoanId;
import com.bank.shared.kernel.domain.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ledger booking of one applied repayment, as the loan applied it: the
 * amount comes in on the clearing account, the principal reduces the loan
 * receivable and the interest is income. Stored in the repayment's own
 * transaction, so it exists exactly when the repayment does, and booked later
 * under {@code loan-repayment:<paymentId>}.
 *
 * The interest income account is null when there is no interest to book.
 */
public record RepaymentBooking(String reference, LoanId loanId, Money principal, Money interest,
                               String receivableAccountId, String clearingAccountId,
                               String interestIncomeAccountId) {

    public RepaymentBooking {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(interest, "interest");
        Objects.requireNonNull(receivableAccountId, "receivableAccountId");
        Objects.requireNonNull(clearingAccountId, "clearingAccountId");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("A repayment booking needs a reference");
        }
        if (!principal.getCurrency().equals(interest.getCurrency())) {
            throw new IllegalArgumentException("Principal and interest of a repayment must share a currency");
        }
        if (principal.getAmount().signum() < 0 || interest.getAmount().signum() < 0) {
            throw new IllegalArgumentException("A repayment cannot book a negative principal or interest");
        }
        if (principal.add(interest).isZero()) {
            throw new IllegalArgumentException("A repayment booking needs a positive amount");
        }
        if (interest.isPositive() && interestIncomeAccountId == null) {
            throw new IllegalArgumentException("Interest needs an income account to be booked to");
        }
    }

    /**
     * The double entry: debit the clearing account by the whole amount, credit
     * the receivable by the principal and the income account by the interest.
     * A line with a zero amount is left out.
     */
    public JournalEntry toJournalEntry() {
        List<JournalEntry.Line> lines = new ArrayList<>();
        lines.add(new JournalEntry.Line(clearingAccountId, JournalEntry.Side.DEBIT, principal.add(interest)));
        if (principal.isPositive()) {
            lines.add(new JournalEntry.Line(receivableAccountId, JournalEntry.Side.CREDIT, principal));
        }
        if (interest.isPositive()) {
            lines.add(new JournalEntry.Line(interestIncomeAccountId, JournalEntry.Side.CREDIT, interest));
        }
        return new JournalEntry(reference, principal.getCurrency(), "Loan repayment " + loanId.getValue(), lines);
    }
}
