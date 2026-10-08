package com.bank.loan.infrastructure.messaging;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Consumes evt.pay.payment.loan-payment-completed.v1 and applies the
 * repayment to the loan schedule through {@link LoanRepaymentUseCase}.
 *
 * Exactly once per (eventId, consumer group): the inbox row and the loan
 * change commit in one transaction. The use case is also idempotent on
 * paymentId, which covers a provider that re-publishes the same payment
 * under a new eventId. Failures are retried by the container's error handler
 * and then sent to evt.ln.loan.dlq.v1 ({@link RepaymentConsumerConfiguration}).
 */
public class LoanPaymentCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(LoanPaymentCompletedListener.class);

    private final ObjectMapper json;
    private final JdbcInbox inbox;
    private final LoanRepaymentUseCase repayments;
    private final TransactionOperations transactions;

    public LoanPaymentCompletedListener(ObjectMapper json, JdbcInbox inbox, LoanRepaymentUseCase repayments,
                                        TransactionOperations transactions) {
        this.json = json;
        this.inbox = inbox;
        this.repayments = repayments;
        this.transactions = transactions;
    }

    @KafkaListener(
        id = "loan-repayment-allocation",
        topics = LoanPaymentCompleted.TOPIC,
        groupId = RepaymentConsumerConfiguration.CONSUMER_GROUP,
        containerFactory = "repaymentListenerContainerFactory")
    public void onLoanPaymentCompleted(ConsumerRecord<String, String> record) {
        process(LoanPaymentCompleted.parse(json, record.value()));
    }

    /** @return true if the event was applied now, false if it was a duplicate */
    boolean process(LoanPaymentCompleted event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!inbox.markProcessed(event.eventId(), RepaymentConsumerConfiguration.CONSUMER_GROUP,
                    LoanPaymentCompleted.EVENT_TYPE, LoanPaymentCompleted.TOPIC)) {
                log.info("Event {} already processed by {}; skipping", event.eventId(),
                    RepaymentConsumerConfiguration.CONSUMER_GROUP);
                return false;
            }
            repayments.recordCompletedLoanPayment(new RecordCompletedLoanPaymentCommand(
                PaymentId.of(event.paymentId()), LoanId.of(event.loanId()), event.actualAmount()));
            return true;
        }));
    }
}
