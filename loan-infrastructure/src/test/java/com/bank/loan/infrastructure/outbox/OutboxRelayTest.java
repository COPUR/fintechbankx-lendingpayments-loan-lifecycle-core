package com.bank.loan.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = inlineTransactions();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7), meters);

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aFailedSendStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("LOAN-1");
        OutboxEventJpaEntity second = row("LOAN-1");
        OutboxEventJpaEntity third = row("LOAN-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("broker down")));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getParkedAt()).isNull();
        assertThat(second.getAttempts()).isZero();   // ADR-021 decision 4: nothing is marked
        assertThat(second.getLastError()).isNull();
        assertThat(third.getAttempts()).isZero();
        assertThat(failures("TimeoutException")).isEqualTo(1.0);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("LOAN-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.ln.loan.created.v1");
        assertThat(record.key()).isEqualTo("LOAN-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("Lending.Loan.Created.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
    }

    /**
     * ADR-021 decision 4 (adr-runbooks #10 e6dd76a): every error that is not
     * a payload error (retryable, authorization, unclassified) never parks a
     * row however long it lasts: the batch stops, nothing is marked, the
     * relay backs off, and outbox.send.failures counts it by exception class.
     */
    @ParameterizedTest
    @MethodSource("nonPayloadErrors")
    void nonPayloadErrorsNeverParkOrMarkARowHoweverLongTheyLast(Exception failure, String exceptionClass) {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("LOAN-R");
        OutboxEventJpaEntity later = row("LOAN-S");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> CompletableFuture.failedFuture(failure));

        for (int run = 0; run < 40; run++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(Duration.ofHours(3));               // 40 runs over five days, always past the backoff
        }

        assertThat(head.getParkedAt()).isNull();
        assertThat(head.getAttempts()).isZero();
        assertThat(head.getLastError()).isNull();
        assertThat(later.getAttempts()).isZero();              // order kept: nothing overtook the head
        assertThat(failures(exceptionClass)).isEqualTo(40.0);
        assertThat(meters.find("outbox.parked.events").counters()).isEmpty();
        verify(kafka, times(40)).send(any(ProducerRecord.class));
    }

    static Stream<Arguments> nonPayloadErrors() {
        return Stream.of(
            Arguments.of(new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)"), "TimeoutException"),
            Arguments.of(new NotEnoughReplicasException("fewer in-sync replicas than required"), "NotEnoughReplicasException"),
            Arguments.of(new NetworkException("disconnected"), "NetworkException"),
            Arguments.of(new KafkaProducerException(null, "send failed", new NotLeaderOrFollowerException("leader moved")),
                "NotLeaderOrFollowerException"),
            Arguments.of(new TopicAuthorizationException(Set.of("evt.ln.loan.created.v1")), "TopicAuthorizationException"),
            Arguments.of(new SaslAuthenticationException("Access denied"), "SaslAuthenticationException"),
            Arguments.of(new KafkaException("Failed to construct kafka producer"), "KafkaException"),
            Arguments.of(new IllegalStateException("something nobody classified"), "IllegalStateException"));
    }

    @Test
    void theRelaysOwnSendTimeoutStopsTheBatchWithoutMarkingTheRow() {
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, new MutableClock(NOW), 50,
            Duration.ofMillis(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("LOAN-W");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        relay.relayOnce();

        assertThat(head.getAttempts()).isZero();
        assertThat(head.getParkedAt()).isNull();
        assertThat(failures("TimeoutException")).isEqualTo(1.0);
    }

    /** After a non-payload failure the relay backs off (1 s doubling to 5 min) and resumes once a run gets through. */
    @Test
    void theRelayBacksOffAfterAFailureAndResumesWhenTheCauseIsFixed() {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("LOAN-A");
        OutboxEventJpaEntity later = row("LOAN-B");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(new TopicAuthorizationException(Set.of("t"))))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(new TopicAuthorizationException(Set.of("t"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isZero();                 // within the 1 s backoff: nothing is tried
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isZero();                 // second failure: backoff doubles to 2 s
        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(2);             // cause fixed: both rows go
        clock.advance(Duration.ofMillis(1));
        assertThat(relay.relayOnce()).isEqualTo(2);             // no backoff left after a run got through
        assertThat(head.getParkedAt()).isNull();
    }

    /**
     * ADR-021 decision 4: payload errors (record too large, serialization,
     * invalid topic) can never be sent as they are: the row is parked at once
     * with its reason and the batch continues.
     */
    @ParameterizedTest
    @MethodSource("payloadErrors")
    void payloadErrorsParkTheRowAtOnceAndTheBatchContinues(Exception failure, String reason) {
        OutboxEventJpaEntity poison = row("LOAN-P");
        OutboxEventJpaEntity later = row("LOAN-Q");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "LOAN-P".equals(record.key())
                ? CompletableFuture.failedFuture(failure)
                : CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getLastError()).isEqualTo(reason);
        assertThat(poison.getParkReason()).isEqualTo("payload error: " + reason);
        assertThat(later.getPublishedAt()).isEqualTo(NOW);
        // Kafka guide 5f7d546: outbox.parked.events{exception=<simple class name>} counts every park (alert on increase).
        String exceptionClass = reason.substring(0, reason.indexOf(':'));
        assertThat(meters.find("outbox.parked.events").tag("exception", exceptionClass).counter()).isNotNull();
        assertThat(meters.get("outbox.parked.events").tag("exception", exceptionClass).counter().count()).isEqualTo(1.0);
        assertThat(failures(exceptionClass)).isZero();
    }

    static Stream<Arguments> payloadErrors() {
        return Stream.of(
            Arguments.of(new RecordTooLargeException("The message is 2000000 bytes"),
                "RecordTooLargeException: The message is 2000000 bytes"),
            Arguments.of(new SerializationException("Can't convert value"),
                "SerializationException: Can't convert value"),
            Arguments.of(new KafkaProducerException(null, "send failed", new InvalidTopicException("bad name")),
                "InvalidTopicException: bad name"));
    }

    @Test
    void interruptedSendStopsTheBatch() {
        OutboxEventJpaEntity first = row("LOAN-I");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first));
        CompletableFuture<SendResult<String, String>> never = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(never);
        Thread.currentThread().interrupt();

        assertThat(relay.relayOnce()).isZero();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getParkedAt()).isNull();
    }

    @Test
    void traceparentIsForwardedWhenTheEventWasRaisedInATracedRequest() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        OutboxEventJpaEntity traced = row("LOAN-T").withTraceparent(traceparent);

        assertThat(header(OutboxRelay.toRecord(traced), "traceparent")).isEqualTo(traceparent);
        assertThat(OutboxRelay.toRecord(row("LOAN-U")).headers().lastHeader("traceparent")).isNull();
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private double failures(String exceptionClass) {
        return meters.find("outbox.send.failures").tag("exception", exceptionClass).counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "Loan", aggregateId, 0L,
            "Lending.Loan.Created.v1", "evt.ln.loan.created.v1", "{}", "corr-9", NOW);
    }

    /** A clock the test moves forward (the relay takes the injected Clock). */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
