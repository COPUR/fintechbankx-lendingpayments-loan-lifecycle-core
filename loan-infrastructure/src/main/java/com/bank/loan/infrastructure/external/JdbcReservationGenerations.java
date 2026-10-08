package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * {@link ReservationGenerations} over sc_ln_loan_lifecycle.credit_reservation_generation.
 * {@link #advance} runs after the disbursement transaction rolled back, so it
 * uses a new transaction of its own.
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
    public void advance(LoanId loanId) {
        newTransaction.executeWithoutResult(status -> jdbc.update("""
            insert into credit_reservation_generation (loan_id, generation, updated_at) values (?, 1, now())
            on conflict (loan_id) do update
               set generation = credit_reservation_generation.generation + 1, updated_at = now()
            """, loanId.getValue()));
    }
}
