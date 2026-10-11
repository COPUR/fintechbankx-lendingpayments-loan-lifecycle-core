package com.bank.loan.infrastructure.messaging;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class LoanPaymentCompletedConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11");
    private static final String EVENT = """
        {"eventId":"6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11","eventType":"Payments.Payment.LoanPaymentCompleted.v1",
         "occurredAt":"2026-10-08T06:00:00Z","aggregateId":"PAY-1","aggregateVersion":3,"correlationId":"corr-1",
         "causationId":null,"producer":"svc-pay-initiation-settlement",
         "data":{"paymentId":"PAY-1","customerId":"CUST-1","loanId":"LOAN-1",
                 "actualAmount":{"amount":"1100.00","currency":"AED"},"transactionReference":"SETTLE-1",
                 "completedAt":"2026-10-08T05:59:59Z","futureField":"ignored"}}
        """;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T07:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void envelopeFieldsAreReadAndUnknownFieldsIgnored() {
        LoanPaymentCompleted event = LoanPaymentCompleted.parse(json, EVENT);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.paymentId()).isEqualTo("PAY-1");
        assertThat(event.loanId()).isEqualTo("LOAN-1");
        assertThat(event.actualAmount()).isEqualTo(Money.aed(new BigDecimal("1100.00")));
    }

    @Test
    void recordsOutsideTheContractAreViolations() {
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, "not json"))
            .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, "[1]"))
            .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, EVENT.replace("LoanPaymentCompleted.v1", "LoanPaymentCompleted.v2")))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("eventType");
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, EVENT.replace("6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11", "nope")))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("UUID");
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, EVENT.replace("\"loanId\":\"LOAN-1\",", "")))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("loanId");
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, EVENT.replace("\"1100.00\"", "\"eleven\"")))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("decimal");
        assertThatThrownBy(() -> LoanPaymentCompleted.parse(json, EVENT.replace("\"AED\"", "\"DIRHAM\"")))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("ISO 4217");
    }

    /**
     * ADR-019 section 4: the consumed record's correlationId (envelope, else
     * the correlationId header) and its eventId as causationId go to the use
     * case as plain ids; no Kafka type crosses the port.
     */
    @Test
    void theConsumedEventsCorrelationAndEventIdAreHandedToTheUseCase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanRepaymentUseCase repayments = mock(LoanRepaymentUseCase.class);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments, transactions);

        listener.onPaymentEvent(paymentRecord("Payments.Payment.LoanPaymentCompleted.v1", EVENT));
        ConsumerRecord<String, String> withoutEnvelopeCorrelation =
            paymentRecord("Payments.Payment.LoanPaymentCompleted.v1", EVENT.replace("\"correlationId\":\"corr-1\",", ""));
        withoutEnvelopeCorrelation.headers().remove("correlationId");
        withoutEnvelopeCorrelation.headers().add("correlationId", "corr-header".getBytes(StandardCharsets.UTF_8));
        listener.onPaymentEvent(withoutEnvelopeCorrelation);

        ArgumentCaptor<RecordCompletedLoanPaymentCommand> commands = ArgumentCaptor.forClass(RecordCompletedLoanPaymentCommand.class);
        verify(repayments, org.mockito.Mockito.times(2)).recordCompletedLoanPayment(commands.capture());
        assertThat(commands.getAllValues().get(0).causation())
            .isEqualTo(new com.bank.loan.domain.port.out.EventCausation("corr-1", EVENT_ID.toString()));
        assertThat(commands.getAllValues().get(1).causation())
            .isEqualTo(new com.bank.loan.domain.port.out.EventCausation("corr-header", EVENT_ID.toString()));
    }

    @Test
    void listenerAppliesTheRepaymentInTheInboxTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanRepaymentUseCase repayments = mock(LoanRepaymentUseCase.class);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        boolean[] inTransaction = new boolean[1];
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                inTransaction[0] = true;
                try {
                    return action.doInTransaction(null);
                } finally {
                    inTransaction[0] = false;
                }
            }
        };
        when(repayments.recordCompletedLoanPayment(any())).thenAnswer(invocation -> {
            assertThat(inTransaction[0]).isTrue();
            return true;
        });

        new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments, transactions)
            .onPaymentEvent(paymentRecord("Payments.Payment.LoanPaymentCompleted.v1", EVENT));

        verify(repayments).recordCompletedLoanPayment(new RecordCompletedLoanPaymentCommand(PaymentId.of("PAY-1"),
            LoanId.of("LOAN-1"), Money.aed(new BigDecimal("1100.00")),
            new com.bank.loan.domain.port.out.EventCausation("corr-1", EVENT_ID.toString())));
        verify(jdbc).update(anyString(), eq(EVENT_ID), eq(RepaymentConsumerConfiguration.CONSUMER_GROUP),
            eq("Payments.Payment.LoanPaymentCompleted.v1"), eq("evt.pay.payment.v1"));
    }

    /** One topic per aggregate (ADR-019): the payment aggregate's topic, not a per-event topic. */
    @Test
    void theConsumerReadsThePaymentAggregateTopic() throws Exception {
        assertThat(LoanPaymentCompleted.TOPIC).isEqualTo("evt.pay.payment.v1");
        KafkaListener listener = LoanPaymentCompletedListener.class.getMethod("onPaymentEvent", ConsumerRecord.class)
            .getAnnotation(KafkaListener.class);
        assertThat(listener.topics()).containsExactly("evt.pay.payment.v1");
        assertThat(listener.groupId()).isEqualTo("cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1");
    }

    /**
     * ADR-019 section 3: the consumer reads the eventType header first and handles only
     * Payments.Payment.LoanPaymentCompleted.v1. Every other type on evt.pay.payment.v1, known or not,
     * is skipped: the listener returns normally (the RECORD ack mode commits the offset), nothing is
     * parsed, no inbox row, no repayment, no exception, so the error handler never dead-letters it.
     */
    @Test
    void otherPaymentEventTypesAreSkippedWithoutFailingOrDeadLettering() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanRepaymentUseCase repayments = mock(LoanRepaymentUseCase.class);
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments,
            TransactionOperations.withoutTransaction());

        for (String other : java.util.List.of("Payments.Payment.Created.v1", "Payments.Payment.ProcessingStarted.v1",
                "Payments.Payment.Completed.v1", "Payments.Payment.Failed.v1", "Payments.Payment.Cancelled.v1",
                "Payments.Payment.Refunded.v1", "Payments.Payment.LoanPaymentCreated.v1",
                "Payments.Payment.LoanPaymentFailed.v1", "Payments.Payment.LoanPaymentCompleted.v2",
                "Payments.Payment.SomethingAddedLater.v1")) {
            // The value is not even parsed: a type this consumer does not handle cannot fail it.
            listener.onPaymentEvent(paymentRecord(other, "{\"eventType\":\"" + other + "\",\"data\":{}}"));
            listener.onPaymentEvent(paymentRecord(other, "not json"));
        }

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
        verify(repayments, never()).recordCompletedLoanPayment(any());
    }

    @Test
    void theOffsetOfASkippedRecordIsCommittedPerRecord() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new RepaymentConsumerConfiguration()
            .repaymentListenerContainerFactory(new KafkaProperties(), mock(KafkaTemplate.class), new SimpleMeterRegistry(),
                CLOCK, 1);

        assertThat(factory.getContainerProperties().getAckMode())
            .isEqualTo(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
    }

    /** The header is required (EventHeaders): without it the record breaks the contract and is dead-lettered once. */
    @Test
    void aRecordWithoutTheEventTypeHeaderBreaksTheContract() {
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json,
            new JdbcInbox(mock(JdbcTemplate.class)), mock(LoanRepaymentUseCase.class),
            TransactionOperations.withoutTransaction());

        assertThatThrownBy(() -> listener.onPaymentEvent(new ConsumerRecord<>("evt.pay.payment.v1", 0, 3L, "PAY-1", EVENT)))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("eventType header");
    }

    /** The eventType header must equal the envelope's eventType. */
    @Test
    void aHeaderThatDisagreesWithTheEnvelopeBreaksTheContract() {
        LoanRepaymentUseCase repayments = mock(LoanRepaymentUseCase.class);
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json,
            new JdbcInbox(mock(JdbcTemplate.class)), repayments, TransactionOperations.withoutTransaction());
        String completedPayment = EVENT.replace("Payments.Payment.LoanPaymentCompleted.v1", "Payments.Payment.Completed.v1");

        assertThatThrownBy(() -> listener.onPaymentEvent(
                paymentRecord("Payments.Payment.LoanPaymentCompleted.v1", completedPayment)))
            .isInstanceOf(ContractViolationException.class).hasMessageContaining("eventType");
        verify(repayments, never()).recordCompletedLoanPayment(any());
    }

    @Test
    void anEventAlreadyInTheInboxIsSkipped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LoanRepaymentUseCase repayments = mock(LoanRepaymentUseCase.class);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments,
            TransactionOperations.withoutTransaction());

        assertThat(listener.process(LoanPaymentCompleted.parse(json, EVENT))).isFalse();
        verify(repayments, never()).recordCompletedLoanPayment(any());
    }

    @Test
    void consumerFollowsThePlatformConventions() {
        Map<String, Object> props = RepaymentConsumerConfiguration.consumerProperties(Map.of("bootstrap.servers", "b:9092"));

        assertThat(props)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1")
            .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            .containsEntry("bootstrap.servers", "b:9092");
    }

    @Test
    void contractViolationGoesStraightToTheLoanDlqWithDlqHeadersAndNoMessage() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(
            new SendResult<>(null, new RecordMetadata(new TopicPartition("evt.ln.loan.dlq.v1", 0), 0, 0, 0, 0, 0))));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DefaultErrorHandler handler = RepaymentConsumerConfiguration.errorHandler(kafka,
            meters.counter("consumer.dlq.messages"), CLOCK);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(LoanPaymentCompleted.TOPIC, 2, 77L, "PAY-1", "not json");
        record.headers().add("eventId", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", "corr-1".getBytes(StandardCharsets.UTF_8));

        boolean recovered = handler.handleOne(new ContractViolationException("Record value is not JSON for customer Jane"),
            record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isTrue();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        ProducerRecord<String, String> dead = sent.getValue();
        assertThat(dead.topic()).isEqualTo("evt.ln.loan.dlq.v1");
        assertThat(dead.key()).isEqualTo("PAY-1");
        assertThat(dead.value()).isEqualTo("not json");
        Headers headers = dead.headers();
        assertThat(text(headers, "dlq-original-topic")).isEqualTo("evt.pay.payment.v1");
        assertThat(text(headers, "dlq-original-partition")).isEqualTo("2");
        assertThat(text(headers, "dlq-original-offset")).isEqualTo("77");
        assertThat(text(headers, "dlq-consumer-group")).isEqualTo("cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1");
        assertThat(text(headers, "dlq-error-class")).isEqualTo(ContractViolationException.class.getName());
        assertThat(text(headers, "dlq-attempts")).isEqualTo("1");
        assertThat(text(headers, "dlq-failed-at")).isEqualTo("2026-10-08T07:00:00Z");
        assertThat(text(headers, "eventId")).isEqualTo(EVENT_ID.toString());
        assertThat(text(headers, "correlationId")).isEqualTo("corr-1");
        headers.forEach(header -> assertThat(new String(header.value(), StandardCharsets.UTF_8)).doesNotContain("Jane"));
        assertThat(meters.counter("consumer.dlq.messages").count()).isEqualTo(1.0);
    }

    @Test
    void aTransientFailureIsRetriedBeforeItIsDeadLettered() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        DefaultErrorHandler handler = RepaymentConsumerConfiguration.errorHandler(kafka,
            new SimpleMeterRegistry().counter("c"), CLOCK);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(LoanPaymentCompleted.TOPIC, 0, 1L, "PAY-1", EVENT);

        boolean recovered = handler.handleOne(new IllegalStateException("database down"), record,
            mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
        assertThat(RepaymentConsumerConfiguration.dlqHeaders(record, new IllegalStateException("x"), CLOCK)
            .lastHeader("dlq-attempts").value()).isEqualTo("4".getBytes(StandardCharsets.UTF_8));
    }

    /** dlq-attempts is the delivery attempt the container recorded, not a guess from the exception type. */
    @Test
    void dlqAttemptsIsTheDeliveryAttemptTheContainerRecorded() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(LoanPaymentCompleted.TOPIC, 0, 5L, "PAY-1", EVENT);
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(Integer.BYTES).putInt(2).array());

        assertThat(text(RepaymentConsumerConfiguration.dlqHeaders(record, new IllegalStateException("x"), CLOCK),
            "dlq-attempts")).isEqualTo("2");
    }

    @Test
    void theContainerRecordsTheDeliveryAttempt() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new RepaymentConsumerConfiguration()
            .repaymentListenerContainerFactory(new KafkaProperties(), mock(KafkaTemplate.class), new SimpleMeterRegistry(),
                CLOCK, 1);

        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
    }

    /** Without the header (not set by a container): non-retryable failures, deserialization included, count once. */
    @Test
    void withoutTheHeaderNonRetryableFailuresIncludingDeserializationCountOnce() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(LoanPaymentCompleted.TOPIC, 0, 6L, "PAY-1", EVENT);
        DeserializationException bad = new DeserializationException("cannot deserialize", new byte[] {1}, false,
            new IllegalStateException("bad bytes"));

        assertThat(text(RepaymentConsumerConfiguration.dlqHeaders(record, bad, CLOCK), "dlq-attempts")).isEqualTo("1");
    }

    private static ConsumerRecord<String, String> paymentRecord(String eventType, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("evt.pay.payment.v1", 0, 42L, "PAY-1", value);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", EVENT_ID.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", "corr-1".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static String text(Headers headers, String name) {
        return headers.lastHeader(name) == null ? null : new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
