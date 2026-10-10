package com.bank.loan.infrastructure.messaging;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;

/**
 * Consumes the payment aggregate topic evt.pay.payment.v1 and applies
 * Payments.Payment.LoanPaymentCompleted.v1 to the loan schedule through
 * {@link LoanRepaymentUseCase}.
 *
 * The topic carries every event type of the payment aggregate (ADR-019,
 * one topic per aggregate). The listener reads the eventType record header
 * first and handles only LoanPaymentCompleted; any other type is skipped
 * without parsing: the listener returns normally, so the offset is committed
 * (ack mode RECORD), nothing fails and nothing is dead-lettered. A record
 * without the header, or whose header disagrees with the envelope, breaks the
 * contract and goes to the DLQ once.
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
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String eventType = eventTypeHeader(record);
        if (!LoanPaymentCompleted.EVENT_TYPE.equals(eventType)) {
            // Another event type of the payment aggregate: not ours. Skip, commit, never fail (ADR-019 section 3).
            log.debug("Skipping {} at {}-{}@{}: {} handles only {}", eventType, record.topic(), record.partition(),
                record.offset(), RepaymentConsumerConfiguration.CONSUMER_GROUP, LoanPaymentCompleted.EVENT_TYPE);
            return;
        }
        process(LoanPaymentCompleted.parse(json, record.value()), record.topic());
    }

    /** The required eventType header (common EventHeaders), UTF-8 text. */
    static String eventTypeHeader(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("eventType");
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new ContractViolationException("Record has no eventType header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    boolean process(LoanPaymentCompleted event) {
        return process(event, LoanPaymentCompleted.TOPIC);
    }

    /** @return true if the event was applied now, false if it was a duplicate */
    boolean process(LoanPaymentCompleted event, String topic) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!inbox.markProcessed(event.eventId(), RepaymentConsumerConfiguration.CONSUMER_GROUP,
                    LoanPaymentCompleted.EVENT_TYPE, topic)) {
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
