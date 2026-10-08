package com.bank.loan.infrastructure.config;

import com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbox gauges carry the names risk and compliance publish, so one
 * alert rule covers every service: outbox_pending_events, outbox_parked_events
 * and outbox_oldest_pending_age_seconds.
 */
class OutboxConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void gaugesUseThePlatformOutboxMetricNames() {
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).thenReturn(4L);
        when(outbox.countByParkedAtIsNotNull()).thenReturn(1L);
        when(outbox.oldestPendingOccurredAt()).thenReturn(Optional.of(NOW.minusSeconds(90)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxConfiguration configuration = new OutboxConfiguration();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        configuration.outboxPendingGauge(registry, outbox);
        configuration.outboxParkedGauge(registry, outbox);
        configuration.outboxOldestPendingAgeGauge(registry, outbox, clock);

        assertThat(registry.get("outbox.pending.events").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("outbox.parked.events").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(90.0);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().getId().getBaseUnit()).isEqualTo("seconds");
    }
}
