package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanStatus;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * {@link ReservationGenerations} over sc_ln_loan_lifecycle.credit_reservation_generation
 * (V3, V6, V9, V10). Every write except {@link #markUsed} runs in a new transaction
 * of its own: it records what is about to be, or was, sent to the customer
 * service, outside any loan transaction (which may roll back). Compare-and-set
 * updates make concurrent requests and replicas agree on which caller
 * compensates a reservation, so a release is sent at most once per
 * reservation.
 */
public class JdbcReservationGenerations implements ReservationGenerations {

    /** SQL list of the statuses for which {@link LoanStatus#isNeverDisbursed()} holds. */
    static final String NEVER_DISBURSED = Arrays.stream(LoanStatus.values())
        .filter(LoanStatus::isNeverDisbursed)
        .map(status -> "'" + status.name() + "'")
        .collect(Collectors.joining(", "));

    private static final String OUTSTANDING = "('RESERVING', 'RESERVED', 'UNCONFIRMED')";
    /** What the sweep reads while release-unanswered is off: UNCONFIRMED rows belong to an operator. */
    private static final String SWEPT_WITHOUT_UNCONFIRMED = "('RESERVING', 'RESERVED')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTransaction;

    public JdbcReservationGenerations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int current(LoanId loanId) {
        List<Integer> rows = jdbc.queryForList(
            "select generation from credit_reservation_generation where loan_id = ?", Integer.class, loanId.getValue());
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    @Override
    public Optional<Reservation> find(LoanId loanId) {
        return jdbc.query("""
            select loan_id, generation, reservation_state, pending_compensation, updated_at
              from credit_reservation_generation where loan_id = ?
            """, JdbcReservationGenerations::reservation, loanId.getValue()).stream().findFirst();
    }

    @Override
    public void beginReservation(LoanId loanId, int generation) {
        newTransaction.executeWithoutResult(status -> {
            jdbc.update("""
                insert into credit_reservation_generation (loan_id, generation, reservation_state, updated_at)
                values (?, ?, 'RESERVING', now())
                on conflict (loan_id) do update
                   set reservation_state = 'RESERVING', updated_at = now()
                 where credit_reservation_generation.generation = excluded.generation
                   and credit_reservation_generation.pending_compensation is null
                   and (credit_reservation_generation.reservation_state is null
                        or credit_reservation_generation.reservation_state in ('RESERVING', 'UNCONFIRMED'))
                """, loanId.getValue(), generation);
            Reservation row = find(loanId).orElseThrow(() -> new IllegalStateException("reservation row not stored"));
            if (row.generation() != generation || row.pendingCompensation() != null
                    || row.state() == null || row.state() == State.USED) {
                throw new IllegalStateException("reservation row of loan " + loanId.getValue() + " is at generation "
                    + row.generation() + " (" + row.state() + "), not at an open generation " + generation);
            }
        });
    }

    @Override
    public void reservationAnswered(LoanId loanId, int generation, boolean accepted) {
        newTransaction.executeWithoutResult(status -> {
            if (accepted) {
                jdbc.update("""
                    update credit_reservation_generation set reservation_state = 'RESERVED', updated_at = now()
                     where loan_id = ? and generation = ? and pending_compensation is null
                       and (reservation_state is null or reservation_state in ('RESERVING', 'UNCONFIRMED'))
                    """, loanId.getValue(), generation);
            } else {
                jdbc.update("""
                    update credit_reservation_generation set reservation_state = null, updated_at = now()
                     where loan_id = ? and generation = ? and reservation_state in ('RESERVING', 'UNCONFIRMED')
                    """, loanId.getValue(), generation);
            }
        });
    }

    @Override
    public boolean markUsed(LoanId loanId) {
        int updated = jdbc.update("update credit_reservation_generation set reservation_state = 'USED', updated_at = now()"
            + " where loan_id = ? and reservation_state in " + OUTSTANDING, loanId.getValue());
        if (updated > 0) {
            return true;
        }
        Integer rows = jdbc.queryForObject(
            "select count(*) from credit_reservation_generation where loan_id = ?", Integer.class, loanId.getValue());
        return rows == null || rows == 0;
    }

    @Override
    public boolean beginCompensation(LoanId loanId, int generation) {
        Integer updated = newTransaction.execute(status -> jdbc.update("""
            insert into credit_reservation_generation (loan_id, generation, pending_compensation, reservation_state, updated_at)
            values (?, ?, ?, null, now())
            on conflict (loan_id) do update
               set generation = excluded.generation, pending_compensation = excluded.pending_compensation,
                   reservation_state = null, updated_at = now()
             where credit_reservation_generation.generation = ?
               and credit_reservation_generation.pending_compensation is null
               and credit_reservation_generation.reservation_state in ('RESERVING', 'RESERVED', 'UNCONFIRMED')
            """, loanId.getValue(), generation + 1, generation, generation));
        return updated != null && updated > 0;
    }

    @Override
    public boolean beginCompensationOfAccepted(LoanId loanId, int generation) {
        Integer updated = newTransaction.execute(status -> jdbc.update("""
            update credit_reservation_generation
               set generation = ?, pending_compensation = ?, reservation_state = null, updated_at = now()
             where loan_id = ? and generation = ? and pending_compensation is null and reservation_state = 'RESERVED'
            """, generation + 1, generation, loanId.getValue(), generation));
        return updated != null && updated > 0;
    }

    @Override
    public boolean beginCompensationOfUnanswered(LoanId loanId, int generation, Instant before) {
        Integer updated = newTransaction.execute(status -> jdbc.update("""
            update credit_reservation_generation
               set generation = ?, pending_compensation = ?, reservation_state = null, updated_at = now()
             where loan_id = ? and generation = ? and pending_compensation is null
               and reservation_state in ('RESERVING', 'UNCONFIRMED') and updated_at < ?
            """, generation + 1, generation, loanId.getValue(), generation, Timestamp.from(before)));
        return updated != null && updated > 0;
    }

    @Override
    public OptionalInt pendingCompensation(LoanId loanId) {
        List<Integer> rows = jdbc.queryForList(
            "select pending_compensation from credit_reservation_generation where loan_id = ? and pending_compensation is not null",
            Integer.class, loanId.getValue());
        return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.getFirst());
    }

    @Override
    public void compensationDone(LoanId loanId, int generation) {
        newTransaction.executeWithoutResult(status -> jdbc.update("""
            update credit_reservation_generation set pending_compensation = null, updated_at = now()
            where loan_id = ? and pending_compensation = ?
            """, loanId.getValue(), generation));
    }

    @Override
    public List<Unresolved> unresolved(Instant before, int limit, boolean includeUnconfirmed) {
        return jdbc.query("""
            select r.loan_id, r.generation, r.reservation_state, r.pending_compensation, r.updated_at,
                   l.customer_id, l.principal_amount, l.currency
              from credit_reservation_generation r join loan l on l.loan_id = r.loan_id
             where l.status in (%s)
               and (r.pending_compensation is not null or r.reservation_state in %s)
               and r.release_refused_code is null
               and r.updated_at < ?
             order by r.updated_at
             limit ?
            """.formatted(NEVER_DISBURSED, includeUnconfirmed ? OUTSTANDING : SWEPT_WITHOUT_UNCONFIRMED),
            (rs, row) -> new Unresolved(reservation(rs, row),
                CustomerId.of(rs.getString("customer_id")),
                Money.of(rs.getBigDecimal("principal_amount"), Currency.getInstance(rs.getString("currency")))),
            Timestamp.from(before), limit);
    }

    @Override
    public boolean markUnconfirmed(LoanId loanId, int generation, Instant before) {
        Integer updated = newTransaction.execute(status -> jdbc.update("""
            update credit_reservation_generation set reservation_state = 'UNCONFIRMED', updated_at = now()
             where loan_id = ? and generation = ? and reservation_state = 'RESERVING' and updated_at < ?
            """, loanId.getValue(), generation, Timestamp.from(before)));
        return updated != null && updated > 0;
    }

    @Override
    public long countUnconfirmed() {
        Long count = jdbc.queryForObject(
            "select count(*) from credit_reservation_generation where reservation_state = 'UNCONFIRMED'", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void releaseRefused(LoanId loanId, int generation, String code) {
        newTransaction.executeWithoutResult(status -> jdbc.update("""
            update credit_reservation_generation set release_refused_code = ?, updated_at = now()
             where loan_id = ? and pending_compensation = ?
            """, code, loanId.getValue(), generation));
    }

    @Override
    public Optional<String> releaseRefusedReason(LoanId loanId) {
        return jdbc.queryForList("select release_refused_code from credit_reservation_generation"
                + " where loan_id = ? and release_refused_code is not null", String.class, loanId.getValue())
            .stream().findFirst();
    }

    @Override
    public long countReleaseRefused() {
        Long count = jdbc.queryForObject(
            "select count(*) from credit_reservation_generation where release_refused_code is not null", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countPending() {
        Long count = jdbc.queryForObject("""
            select count(*) from credit_reservation_generation r join loan l on l.loan_id = r.loan_id
             where l.status in (%s)
               and (r.pending_compensation is not null or r.reservation_state in %s)
            """.formatted(NEVER_DISBURSED, OUTSTANDING), Long.class);
        return count == null ? 0 : count;
    }

    private static Reservation reservation(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("reservation_state");
        int pending = rs.getInt("pending_compensation");
        Integer pendingCompensation = rs.wasNull() ? null : pending;
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new Reservation(LoanId.of(rs.getString("loan_id")), rs.getInt("generation"),
            state == null ? null : State.valueOf(state), pendingCompensation,
            updatedAt == null ? null : updatedAt.toInstant());
    }
}
