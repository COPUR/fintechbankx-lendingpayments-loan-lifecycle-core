package com.bank.loan.domain.port.in;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.port.out.EventCausation;
import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/**
 * The payment context settled a loan repayment
 * (Payments.Payment.LoanPaymentCompleted.v1); paymentId is the payment service's id.
 * {@code causation} names the consumed event (its correlationId, and its
 * eventId as causationId) for the loan events the repayment raises
 * (ADR-019 section 4); null only when there is no such message.
 */
public record RecordCompletedLoanPaymentCommand(PaymentId paymentId, LoanId loanId, Money amount,
                                                EventCausation causation) {

    public RecordCompletedLoanPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(amount, "amount");
    }

    public RecordCompletedLoanPaymentCommand(PaymentId paymentId, LoanId loanId, Money amount) {
        this(paymentId, loanId, amount, null);
    }
}
