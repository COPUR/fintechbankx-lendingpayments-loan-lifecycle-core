package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.RepaymentBooking;
import com.bank.loan.domain.port.out.RepaymentBookings;
import com.bank.shared.kernel.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Repayment bookings in PostgreSQL. {@link #record} joins the repayment's
 * transaction (the JdbcTemplate uses the transaction bound to the thread), so
 * the booking commits or rolls back with the repayment. The sweep reads and
 * marks the rest outside any transaction of its own.
 */
@Repository
public class JdbcRepaymentBookings implements RepaymentBookings {

    private static final String INSERT = """
        INSERT INTO repayment_booking (reference, loan_id, currency, principal_amount, interest_amount,
            receivable_account_id, clearing_account_id, interest_income_account_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String PENDING = """
        SELECT reference, loan_id, currency, principal_amount, interest_amount,
            receivable_account_id, clearing_account_id, interest_income_account_id
        FROM repayment_booking
        WHERE booked_at IS NULL AND NOT parked
        ORDER BY created_at, reference
        LIMIT ?
        """;

    private static final String MARK_BOOKED = """
        UPDATE repayment_booking SET booked_at = ?, journal_entry_id = ?, last_error = NULL
        WHERE reference = ? AND booked_at IS NULL
        """;

    private static final String ATTEMPT = """
        UPDATE repayment_booking SET attempts = attempts + 1, last_error = ?
        WHERE reference = ? AND booked_at IS NULL
        """;

    private static final String PARK = """
        UPDATE repayment_booking SET parked = TRUE, attempts = attempts + 1, last_error = ?
        WHERE reference = ? AND booked_at IS NULL
        """;

    private static final String COUNT_PARKED = """
        SELECT count(*) FROM repayment_booking WHERE parked AND booked_at IS NULL
        """;

    private static final String OLDEST_PENDING = """
        SELECT min(created_at) FROM repayment_booking WHERE booked_at IS NULL AND NOT parked
        """;

    static final String UNREADABLE = "UNREADABLE_BOOKING";

    private static final Logger log = LoggerFactory.getLogger(JdbcRepaymentBookings.class);

    private final JdbcTemplate jdbc;

    public JdbcRepaymentBookings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(RepaymentBooking booking) {
        jdbc.update(INSERT,
            booking.reference(),
            booking.loanId().getValue(),
            booking.principal().getCurrency().getCurrencyCode(),
            booking.principal().getAmount(),
            booking.interest().getAmount(),
            booking.receivableAccountId(),
            booking.clearingAccountId(),
            booking.interestIncomeAccountId());
    }

    @Override
    public List<RepaymentBooking> pending(int limit) {
        List<RepaymentBooking> readable = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        jdbc.query(PENDING, (RowCallbackHandler) rs -> {
            try {
                readable.add(toBooking(rs));
            } catch (RuntimeException unreadableRow) {
                unreadable.add(rs.getString("reference"));
            }
        }, limit);
        for (String reference : unreadable) {
            park(reference, UNREADABLE);
            log.error("Repayment booking {} cannot be read back as a booking; parked for an operator", reference);
        }
        return readable;
    }

    @Override
    public void markBooked(String reference, String journalEntryId, Instant bookedAt) {
        jdbc.update(MARK_BOOKED, Timestamp.from(bookedAt), journalEntryId, reference);
    }

    @Override
    public void recordAttempt(String reference, String code) {
        jdbc.update(ATTEMPT, code, reference);
    }

    @Override
    public void park(String reference, String code) {
        jdbc.update(PARK, code, reference);
    }

    @Override
    public long countParked() {
        Long parked = jdbc.queryForObject(COUNT_PARKED, Long.class);
        return parked == null ? 0 : parked;
    }

    @Override
    public Optional<Instant> oldestPending() {
        return Optional.ofNullable(jdbc.queryForObject(OLDEST_PENDING, Timestamp.class)).map(Timestamp::toInstant);
    }

    private static RepaymentBooking toBooking(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new RepaymentBooking(
            rs.getString("reference"),
            LoanId.of(rs.getString("loan_id")),
            money(rs.getBigDecimal("principal_amount"), currency),
            money(rs.getBigDecimal("interest_amount"), currency),
            rs.getString("receivable_account_id"),
            rs.getString("clearing_account_id"),
            rs.getString("interest_income_account_id"));
    }

    /** The column keeps four decimals; the amount itself has the currency's own scale. */
    private static Money money(BigDecimal stored, Currency currency) {
        return Money.of(stored.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency);
    }
}
