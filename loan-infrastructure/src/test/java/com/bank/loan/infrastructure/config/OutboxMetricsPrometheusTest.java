package com.bank.loan.infrastructure.config;

import com.bank.loan.infrastructure.outbox.OutboxRelay;
import com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbox meters under the names in the platform Kafka guide (5f7d546):
 * gauge outbox_oldest_pending_age_seconds, counters outbox_send_failures_total
 * and outbox_parked_events_total, both tagged exception only. They must all
 * register in one Prometheus registry (a gauge and a counter sharing a base
 * name would be refused), alongside the pending and parked-rows gauges.
 */
class OutboxMetricsPrometheusTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void allOutboxMetersRegisterUnderTheirPlatformPrometheusNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countByParkedAtIsNotNull()).thenReturn(2L);
        when(outbox.oldestPendingOccurredAt()).thenReturn(Optional.of(NOW.minusSeconds(30)));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OutboxConfiguration configuration = new OutboxConfiguration();
        configuration.outboxPendingGauge(registry, outbox);
        configuration.outboxParkedRowsGauge(registry, outbox);
        configuration.outboxOldestPendingAgeGauge(registry, outbox, clock);
        OutboxRelay relay = new OutboxRelay(outbox, mock(KafkaTemplate.class), mock(TransactionTemplate.class), clock,
            100, Duration.ofSeconds(1), Duration.ofDays(7), registry);

        relay.recordSendFailure(new org.apache.kafka.common.errors.TimeoutException("x"));
        relay.recordParked("RecordTooLargeException");

        assertThat(registry.scrape())
            .contains("outbox_oldest_pending_age_seconds 30.0")
            .contains("outbox_send_failures_total{exception=\"TimeoutException\"} 1.0")
            .contains("outbox_parked_events_total{exception=\"RecordTooLargeException\"} 1.0")
            .contains("outbox_parked_rows 2.0")
            .contains("outbox_pending_events ");
    }
}
