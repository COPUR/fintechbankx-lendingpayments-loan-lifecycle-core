package com.bank.loan.domain.port.in;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/**
 * The payment context settled a loan repayment
 * (Payments.Payment.LoanPaymentCompleted.v1); paymentId is the payment service's id.
 */
public record RecordCompletedLoanPaymentCommand(PaymentId paymentId, LoanId loanId, Money amount) {

    public RecordCompletedLoanPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(amount, "amount");
    }
}
