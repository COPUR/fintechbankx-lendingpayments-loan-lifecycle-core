package com.bank.loan.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import io.micrometer.core.instrument.MeterRegistry;
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
 * A failed send is handled per ADR-021 decision 4 (adr-runbooks #10 e6dd76a):
 * <ul>
 *   <li>payload errors (RecordTooLarge, Serialization, InvalidTopic): the row
 *   can never be sent as it is; it is parked at once (parked_at, park_reason,
 *   last_error), counted in outbox.parked.events tagged with the exception's
 *   simple class name (alert on any increase), and the batch continues;</li>
 *   <li>every other error (retryable broker or network errors, the relay's own
 *   send timeout, authorization, anything unclassified) never parks a row,
 *   however long it lasts: the batch stops, nothing is marked, the relay backs
 *   off (1 s doubling to 5 min) and counts the failure in outbox.send.failures
 *   tagged with the exception's simple class name (never an id).</li>
 * </ul>
 * Only an operator parks such a row, by hand and with a recorded park_reason
 * (runbook section 7); the relay counts each such park once, under its lock,
 * as outbox.parked.events{exception="OperatorPark"} (park_counted, V8). The outage alert is outbox_oldest_pending_age_seconds.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6C6E5F6F7574L; // "ln_out"
    static final String SEND_FAILURES = "outbox.send.failures";
    static final String PARKED_EVENTS = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";
    static final Duration BACKOFF_START = Duration.ofSeconds(1);
    static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final MeterRegistry meters;
    // Only the scheduler thread runs the relay.
    private Instant backoffUntil;
    private int failureStreak;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.meters = meters;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (backoffUntil != null && clock.instant().isBefore(backoffUntil)) {
            return 0;
        }
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            countOperatorParks();
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            boolean failed = false;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed = true;
                    break;
                } catch (Exception e) {
                    if (isPayloadError(e)) {
                        String reason = describe(e);
                        recordParked(unwrap(e).getClass().getSimpleName());
                        row.markFailed(reason);
                        row.park(clock.instant(), "payload error: " + reason);
                        log.error("Outbox relay parked event {} for {}: payload error {}; later events continue (ADR-021 decision 4)",
                            row.getEventId(), row.getTopic(), reason);
                        continue;
                    }
                    failed = true;
                    backOff(e);
                    break;
                }
            }
            if (!failed) {
                failureStreak = 0;
                backoffUntil = null;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    /** ADR-021 decision 4 payload errors: the record itself can never be sent. */
    static boolean isPayloadError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** Counts a failed send, tagged with the unwrapped exception's simple class name only (never ids). */
    public void recordSendFailure(Throwable failure) {
        meters.counter(SEND_FAILURES, "exception", unwrap(failure).getClass().getSimpleName()).increment();
    }

    /** Counts a parked row (outbox_parked_events_total); alert on any increase. */
    public void recordParked(String exceptionClass) {
        meters.counter(PARKED_EVENTS, "exception", exceptionClass).increment();
    }

    /**
     * Operator parks happen in SQL (runbook section 7); count each once with
     * exception="OperatorPark". Runs only while this replica holds the relay
     * lock, so one replica counts.
     */
    private void countOperatorParks() {
        for (OutboxEventJpaEntity parked : outbox.findUncountedParks()) {
            parked.markParkCounted();
            recordParked(OPERATOR_PARK);
            log.warn("Outbox event {} for {} was parked by an operator", parked.getEventId(), parked.getTopic());
        }
    }

    /** Not the row's fault: count it by class, mark nothing, back off (1 s doubling to 5 min). */
    private void backOff(Exception e) {
        Throwable cause = unwrap(e);
        recordSendFailure(e);
        failureStreak++;
        Duration backoff = BACKOFF_START.multipliedBy(1L << Math.min(failureStreak - 1, 20));
        if (backoff.compareTo(BACKOFF_MAX) > 0) {
            backoff = BACKOFF_MAX;
        }
        backoffUntil = clock.instant().plus(backoff);
        log.warn("Outbox relay send failed ({}, retryable={}); nothing marked, retrying in {} (ADR-021 decision 4)",
            cause.getClass().getSimpleName(), isRetryable(e), backoff, e);
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
        Throwable cause = unwrap(failure);
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
