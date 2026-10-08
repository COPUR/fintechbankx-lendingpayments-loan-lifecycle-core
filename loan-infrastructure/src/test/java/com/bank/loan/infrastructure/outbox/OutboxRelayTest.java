package com.bank.loan.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).isEqualTo("ExecutionException");
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

    @Test
    void aPoisonRowIsParkedAfterMaxAttemptsAndLaterRowsAreStillPublished() {
        OutboxRelay strict = new OutboxRelay(outbox, kafka, transactions, Clock.fixed(NOW, ZoneOffset.UTC), 50,
            Duration.ofSeconds(1), Duration.ofDays(7), 3);
        OutboxEventJpaEntity poison = row("LOAN-P");
        OutboxEventJpaEntity later = row("LOAN-Q");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "LOAN-P".equals(record.key())
                ? CompletableFuture.failedFuture(new IllegalStateException("record too large"))
                : CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        // runs 1 and 2: the poison row blocks (order kept), run 3 parks it and moves on
        assertThat(strict.relayOnce()).isZero();
        assertThat(strict.relayOnce()).isZero();
        assertThat(later.getPublishedAt()).isNull();
        assertThat(strict.relayOnce()).isEqualTo(1);

        assertThat(poison.getAttempts()).isEqualTo(3);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(later.getPublishedAt()).isEqualTo(NOW);
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

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
