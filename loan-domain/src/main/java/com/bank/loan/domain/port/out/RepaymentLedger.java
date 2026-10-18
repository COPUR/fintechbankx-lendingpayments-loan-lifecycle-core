package com.bank.loan.domain.port.out;

import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.Repayment;

import java.util.Optional;

/**
 * Out-port for the repayment history of loans (tables repayment and
 * repayment_allocation). Written in the same transaction as the loan.
 */
public interface RepaymentLedger {

    /** True if a repayment with this payment id was already applied. */
    boolean contains(PaymentId paymentId);

    /** The repayment recorded under (requestScope, idempotencyKey), if any. */
    Optional<Repayment> findByIdempotencyKey(String requestScope, String idempotencyKey);

    void record(Repayment repayment);
}
