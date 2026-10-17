package com.bank.loan.domain;

import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A repayment applied to a loan and how it was allocated to the schedule.
 * The loan context owns this history (repayment / repayment_allocation);
 * the payment context owns only the money movement itself.
 *
 * @param requestScope   who sent the request (the caller's customer id or
 *                       principal); null unless an idempotency key was given
 * @param idempotencyKey the caller's x-idempotency-key, if any
 */
public record Repayment(PaymentId paymentId, LoanId loanId, Money amount, List<InstallmentAllocation> allocations,
                        boolean loanFullyPaid, Instant appliedAt, Source source,
                        String requestScope, String idempotencyKey) {

    /** Where the repayment came from. */
    public enum Source {
        /** POST /api/v1/loans/{id}/payments on this service */
        API,
        /** Payments.Payment.LoanPaymentCompleted.v1 on evt.pay.payment.v1 from the payment service */
        PAYMENT_EVENT,
        /** monolith history, loaded by db/backfill */
        MONOLITH
    }

    public Repayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(source, "source");
        allocations = List.copyOf(allocations);
        if ((requestScope == null) != (idempotencyKey == null)) {
            throw new IllegalArgumentException("Idempotency key and request scope go together");
        }
    }

    public static Repayment of(PaymentResult result, Money amount, Instant appliedAt, Source source,
                               String requestScope, String idempotencyKey) {
        return new Repayment(result.getPaymentId(), result.getLoanId(), amount, result.getAllocations(),
            result.isLoanFullyPaid(), appliedAt, source, requestScope, idempotencyKey);
    }
}
