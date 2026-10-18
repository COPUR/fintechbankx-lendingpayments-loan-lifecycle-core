package com.bank.loan.infrastructure.messaging;

import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

/**
 * The repayment consumer of the payment aggregate topic evt.pay.payment.v1
 * (platform Kafka client conventions); it handles
 * Payments.Payment.LoanPaymentCompleted.v1 and skips the other event types:
 * group cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1, read_committed,
 * no auto-commit, offsets acknowledged per record after the DB commit, earliest
 * for a new group. Three retries (1 s, 2 s, 4 s), then the record goes to the
 * loan namespace dead-letter topic evt.ln.loan.dlq.v1 unchanged, with the
 * dlq-* headers and the exception class only (never the message, which may
 * carry personal data). Contract violations and rule violations that cannot
 * heal skip the retries.
 *
 * Off unless loan.repayment-consumer.enabled=true: the topic and the DLQ must
 * exist in the platform catalog first.
 */
@Configuration
@ConditionalOnProperty(name = "loan.repayment-consumer.enabled", havingValue = "true")
public class RepaymentConsumerConfiguration {

    public static final String CONSUMER_GROUP = "cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1";
    public static final String DLQ_TOPIC = "evt.ln.loan.dlq.v1";
    static final int RETRIES = 3;

    @Bean
    JdbcInbox repaymentInbox(JdbcTemplate jdbc) {
        return new JdbcInbox(jdbc);
    }

    @Bean
    LoanPaymentCompletedListener loanPaymentCompletedListener(ObjectMapper json, JdbcInbox inbox,
                                                              LoanRepaymentUseCase repayments,
                                                              TransactionOperations transactions) {
        return new LoanPaymentCompletedListener(json, inbox, repayments, transactions);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> repaymentListenerContainerFactory(
            KafkaProperties kafkaProperties,
            KafkaTemplate<String, String> kafka,
            MeterRegistry meters,
            Clock clock,
            @Value("${loan.repayment-consumer.concurrency:1}") int concurrency) {
        Map<String, Object> props = consumerProperties(kafkaProperties.buildConsumerProperties(null));
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // The container stamps each delivery with KafkaHeaders.DELIVERY_ATTEMPT (from the
        // error handler's FailedRecordTracker); dlq-attempts reports that number.
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.setCommonErrorHandler(errorHandler(kafka, meters.counter("consumer.dlq.messages", "group", CONSUMER_GROUP), clock));
        return factory;
    }

    static Map<String, Object> consumerProperties(Map<String, Object> base) {
        Map<String, Object> props = new java.util.HashMap<>(base);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, Counter deadLettered, Clock clock) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
            (record, ex) -> new TopicPartition(DLQ_TOPIC, -1));
        // Exception class only: no message and no stack trace (may carry personal data).
        recoverer.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG,
            DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        recoverer.setHeadersFunction((record, ex) -> {
            deadLettered.increment();
            return dlqHeaders(record, ex, clock);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(ContractViolationException.class, DeserializationException.class,
            IllegalArgumentException.class);
        return handler;
    }

    /** DeadLetterHeaders of the platform envelope schema; exception class only. */
    static Headers dlqHeaders(ConsumerRecord<?, ?> record, Exception ex, Clock clock) {
        // Unwrap the listener wrappers, but keep a DeserializationException: it is the failure itself.
        Throwable cause = ex.getCause() != null && !(ex instanceof DeserializationException)
            && ex.getClass().getName().startsWith("org.springframework.kafka") ? ex.getCause() : ex;
        RecordHeaders headers = new RecordHeaders();
        copy(record, headers, "eventType");
        copy(record, headers, "eventId");
        copy(record, headers, "correlationId");
        add(headers, "dlq-original-topic", record.topic());
        add(headers, "dlq-original-partition", String.valueOf(record.partition()));
        add(headers, "dlq-original-offset", String.valueOf(record.offset()));
        add(headers, "dlq-consumer-group", CONSUMER_GROUP);
        add(headers, "dlq-attempts", String.valueOf(attempts(record, cause)));
        add(headers, "dlq-error-class", cause.getClass().getName());
        add(headers, "dlq-failed-at", clock.instant().toString());
        return headers;
    }

    /**
     * The delivery attempt that failed, as recorded by the container
     * (KafkaHeaders.DELIVERY_ATTEMPT, deliveryAttemptHeader on). Only without
     * that header (a record not delivered by this container) is it inferred:
     * 1 for the failures that skip retries, otherwise all retries.
     */
    static int attempts(ConsumerRecord<?, ?> record, Throwable cause) {
        Header delivery = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (delivery != null && delivery.value() != null && delivery.value().length == Integer.BYTES) {
            return ByteBuffer.wrap(delivery.value()).getInt();
        }
        return cause instanceof ContractViolationException || cause instanceof IllegalArgumentException
            || cause instanceof DeserializationException ? 1 : RETRIES + 1;
    }

    private static void copy(ConsumerRecord<?, ?> record, RecordHeaders headers, String name) {
        var header = record.headers().lastHeader(name);
        if (header != null) {
            headers.add(name, header.value());
        }
    }

    private static void add(RecordHeaders headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
