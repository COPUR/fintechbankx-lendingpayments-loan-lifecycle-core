package com.bank.loan.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
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
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7));

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
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(second.getLastError()).isEqualTo("TimeoutException: broker down");
        assertThat(third.getAttempts()).isZero();
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
     * Retryable failures (Kafka RetriableException: timeouts, not enough
     * replicas, broker unavailable; or the relay's own send timeout) never
     * count toward parking: an outage only delays events, however many
     * attempts it takes.
     */
    @ParameterizedTest
    @MethodSource("retryableFailures")
    void retryableFailuresNeverCountTowardParking(Exception failure) {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7));
        OutboxEventJpaEntity head = row("LOAN-R");
        OutboxEventJpaEntity later = row("LOAN-S");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> CompletableFuture.failedFuture(failure));

        for (int run = 0; run < 50; run++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(Duration.ofMinutes(20)); // 50 runs over 16 h 40 min
        }

        assertThat(head.getAttempts()).isEqualTo(50);
        assertThat(head.getParkedAt()).isNull();
        assertThat(head.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(later.getAttempts()).isZero(); // order kept: nothing overtook the head
        verify(kafka, times(50)).send(any(ProducerRecord.class));
    }

    static Stream<Exception> retryableFailures() {
        return Stream.of(
            new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)"),
            new NotEnoughReplicasException("Messages are rejected since there are fewer in-sync replicas than required"),
            new NetworkException("The server disconnected before a response was received"),
            new KafkaProducerException(null, "send failed", new NotLeaderOrFollowerException("leader moved")));
    }

    @Test
    void theRelaysOwnSendTimeoutIsRetryable() {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
            Duration.ofMillis(1), Duration.ofDays(7));
        OutboxEventJpaEntity head = row("LOAN-W");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        for (int run = 0; run < 12; run++) {
            relay.relayOnce();
        }

        assertThat(head.getAttempts()).isEqualTo(12);
        assertThat(head.getParkedAt()).isNull();
    }

    /** A retryable failure parks the row only after 24 h of continuous failure from its first failure. */
    @Test
    void aRowFailingRetryablyParksOnlyAfter24HoursFromItsFirstFailure() {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7));
        OutboxEventJpaEntity head = row("LOAN-H");
        OutboxEventJpaEntity later = row("LOAN-L");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "LOAN-H".equals(record.key())
                ? CompletableFuture.failedFuture(new LeaderNotAvailableException("There is no leader for this topic-partition"))
                : CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        assertThat(relay.relayOnce()).isZero();               // first failure at NOW
        clock.advance(Duration.ofHours(24));
        assertThat(relay.relayOnce()).isZero();               // exactly 24 h: still retried
        assertThat(head.getParkedAt()).isNull();
        assertThat(later.getPublishedAt()).isNull();

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);           // past 24 h: parked, the batch moves on

        assertThat(head.getAttempts()).isEqualTo(3);
        assertThat(head.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(head.getParkedAt()).isEqualTo(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(head.getPublishedAt()).isNull();
        assertThat(later.getPublishedAt()).isEqualTo(clock.instant());
    }

    /**
     * Failures Kafka will never accept on a retry (record too large,
     * serialization, authorization, invalid topic) park the row at once and
     * the batch continues.
     */
    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void nonRetryableFailuresParkTheRowAtOnce(Exception failure, String lastError) {
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
        assertThat(poison.getLastError()).isEqualTo(lastError);
        assertThat(later.getPublishedAt()).isEqualTo(NOW);
    }

    static Stream<Arguments> nonRetryableFailures() {
        return Stream.of(
            Arguments.of(new RecordTooLargeException("The message is 2000000 bytes"),
                "RecordTooLargeException: The message is 2000000 bytes"),
            Arguments.of(new SerializationException("Can't convert value"),
                "SerializationException: Can't convert value"),
            Arguments.of(new TopicAuthorizationException(Set.of("evt.ln.loan.created.v1")),
                "TopicAuthorizationException: Not authorized to access topics: [evt.ln.loan.created.v1]"),
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
        assertThat(first.getLastError()).isEqualTo("interrupted");
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
