package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.OptionalInt;

/**
 * {@link ReservationGenerations} over sc_ln_loan_lifecycle.credit_reservation_generation.
 * {@link #beginCompensation} and {@link #compensationDone} run after the
 * disbursement transaction rolled back, so they use new transactions of their own.
 */
public class JdbcReservationGenerations implements ReservationGenerations {

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
    public void beginCompensation(LoanId loanId, int generation) {
        newTransaction.executeWithoutResult(status -> jdbc.update("""
            insert into credit_reservation_generation (loan_id, generation, pending_compensation, updated_at)
            values (?, ?, ?, now())
            on conflict (loan_id) do update
               set generation = excluded.generation, pending_compensation = excluded.pending_compensation,
                   updated_at = now()
            """, loanId.getValue(), generation + 1, generation));
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
}
