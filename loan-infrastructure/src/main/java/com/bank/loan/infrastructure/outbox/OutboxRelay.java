package com.bank.loan.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering an aggregate's events. Consumers de-duplicate
 * on eventId, which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed (same policy as risk-decisioning
 * and compliance-evidence):
 * <ul>
 *   <li>retryable (a Kafka {@link RetriableException}: timeouts, not enough
 *   replicas, broker unavailable, leader moves; or the relay's own send
 *   timeout): the batch stops and the row is retried on the next run, so
 *   later events cannot overtake it. Retryable failures never count toward
 *   parking; the row is parked only once it has been failing continuously for
 *   longer than {@code retryableParkAfter} (default 24 h) since its first
 *   failure (first_failed_at), so an ordinary outage only delays events;</li>
 *   <li>non-retryable (RecordTooLarge, Serialization, TopicAuthorization,
 *   InvalidTopic, anything else that is not retriable): the row is parked at
 *   once (parked_at, reason in last_error) and the batch continues.</li>
 * </ul>
 * Parked rows show in the outbox_parked_events gauge; re-drive them by
 * clearing parked_at and first_failed_at once the cause is fixed (runbook).
 * The outage alert is outbox_oldest_pending_age_seconds.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6C6E5F6F7574L; // "ln_out"
    static final Duration DEFAULT_RETRYABLE_PARK_AFTER = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final Duration retryableParkAfter;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention) {
        this(outbox, kafka, transactions, clock, batchSize, sendTimeout, retention, DEFAULT_RETRYABLE_PARK_AFTER);
    }

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, Duration retryableParkAfter) {
        if (retryableParkAfter == null || retryableParkAfter.isZero() || retryableParkAfter.isNegative()) {
            throw new IllegalArgumentException("loan.outbox.relay.retryable-park-after must be positive");
        }
        this.retryableParkAfter = retryableParkAfter;
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    row.markFailed("interrupted", clock.instant());
                    break;
                } catch (Exception e) {
                    Instant now = clock.instant();
                    row.markFailed(describe(e), now);
                    if (isRetryable(e) && !now.isAfter(row.getFirstFailedAt().plus(retryableParkAfter))) {
                        log.warn("Outbox relay could not publish event {} to {} (attempt {}, failing since {}); will retry",
                            row.getEventId(), row.getTopic(), row.getAttempts(), row.getFirstFailedAt(), e);
                        break;
                    }
                    row.park(now);
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; later events continue, replay it by hand",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    /** Retryable: a Kafka RetriableException anywhere in the cause chain, or the relay's own send timeout. */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
