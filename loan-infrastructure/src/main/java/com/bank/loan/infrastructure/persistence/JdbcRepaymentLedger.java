package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.InstallmentAllocation;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.Repayment;
import com.bank.loan.domain.port.out.RepaymentLedger;
import com.bank.shared.kernel.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Out-port adapter for {@link RepaymentLedger} over sc_ln_loan_lifecycle.repayment
 * and repayment_allocation. Runs in the caller's transaction (the loan was
 * saved and flushed just before), so the history commits with the loan.
 */
@Repository
public class JdbcRepaymentLedger implements RepaymentLedger {

    private final JdbcTemplate jdbc;

    public JdbcRepaymentLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean contains(PaymentId paymentId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from repayment where payment_id = ?", Integer.class, paymentId.getValue());
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Repayment> findByIdempotencyKey(String requestScope, String idempotencyKey) {
        return jdbc.query("""
                select payment_id, loan_id, amount, currency, loan_fully_paid, applied_at, source
                  from repayment
                 where request_scope = ? and idempotency_key = ?
                """,
            (rs, row) -> new Repayment(
                PaymentId.of(rs.getString("payment_id")),
                LoanId.of(rs.getString("loan_id")),
                Money.of(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                List.of(),
                rs.getBoolean("loan_fully_paid"),
                rs.getTimestamp("applied_at").toInstant(),
                Repayment.Source.valueOf(rs.getString("source")),
                requestScope,
                idempotencyKey),
            requestScope, idempotencyKey).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Repayment repayment) {
        String loanId = repayment.loanId().getValue();
        Timestamp appliedAt = Timestamp.from(repayment.appliedAt());
        jdbc.update("""
                insert into repayment (payment_id, loan_id, amount, currency, installments_paid, loan_fully_paid,
                                       applied_at, source, request_scope, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            repayment.paymentId().getValue(), loanId, repayment.amount().getAmount(),
            repayment.amount().getCurrency().getCurrencyCode(), installmentsPaid(loanId, repayment),
            repayment.loanFullyPaid(), appliedAt, repayment.source().name(),
            repayment.requestScope(), repayment.idempotencyKey());

        if (repayment.allocations().isEmpty()) {
            return;
        }
        Map<Integer, UUID> installmentIds = jdbc.query(
                "select installment_number, installment_id from loan_installment where loan_id = ?",
                (rs, row) -> Map.entry(rs.getInt(1), rs.getObject(2, UUID.class)), loanId)
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        jdbc.batchUpdate("""
                insert into repayment_allocation (payment_id, installment_id, loan_id, installment_number, amount,
                                                  principal, interest, currency, allocated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            repayment.allocations().stream().map(allocation -> new Object[] {
                repayment.paymentId().getValue(),
                requireInstallment(installmentIds, allocation, loanId),
                loanId,
                allocation.installmentNumber(),
                allocation.amount().getAmount(),
                allocation.principal().getAmount(),
                allocation.interest().getAmount(),
                allocation.amount().getCurrency().getCurrencyCode(),
                appliedAt
            }).toList());
    }

    private int installmentsPaid(String loanId, Repayment repayment) {
        if (repayment.allocations().isEmpty()) {
            return 0;
        }
        List<Integer> numbers = repayment.allocations().stream().map(InstallmentAllocation::installmentNumber).toList();
        String placeholders = numbers.stream().map(n -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[numbers.size() + 1];
        args[0] = loanId;
        for (int i = 0; i < numbers.size(); i++) {
            args[i + 1] = numbers.get(i);
        }
        Integer paid = jdbc.queryForObject(
            "select count(*) from loan_installment where loan_id = ? and status = 'PAID' and installment_number in ("
                + placeholders + ")", Integer.class, args);
        return paid == null ? 0 : paid;
    }

    private static UUID requireInstallment(Map<Integer, UUID> ids, InstallmentAllocation allocation, String loanId) {
        UUID id = ids.get(allocation.installmentNumber());
        if (id == null) {
            throw new IllegalStateException("Loan " + loanId + " has no installment " + allocation.installmentNumber());
        }
        return id;
    }
}
