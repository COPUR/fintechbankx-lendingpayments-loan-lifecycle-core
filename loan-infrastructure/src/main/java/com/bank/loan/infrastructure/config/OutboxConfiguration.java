package com.bank.loan.infrastructure.config;

import com.bank.loan.infrastructure.outbox.LoanEventEnvelopeFactory;
import com.bank.loan.infrastructure.outbox.OutboxRelay;
import com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

    @Bean
    LoanEventEnvelopeFactory loanEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new LoanEventEnvelopeFactory(objectMapper);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Backlog of events not yet on Kafka (platform name outbox_pending_events).
     * Alert on growth: it means the relay or the brokers are down while loans
     * keep changing.
     */
    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
            .description("Loan events written to the outbox but not yet published to Kafka")
            .register(registry);
    }

    /**
     * Rows parked right now (outbox_parked_rows). Not "outbox.parked.events":
     * that is the relay's counter (outbox_parked_events_total) and Prometheus
     * refuses a gauge and a counter sharing a base name.
     */
    @Bean
    Gauge outboxParkedRowsGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.parked.rows", outbox, SpringDataOutboxRepository::countByParkedAtIsNotNull)
            .description("Loan events parked right now (payload error, or by an operator with a reason)")
            .register(registry);
    }

    /**
     * Age of the oldest event still waiting (outbox_oldest_pending_age_seconds,
     * the platform meter name); 0 when the backlog is empty. The outage alert:
     * under ADR-021 decision 4 only payload errors park a row, so any other
     * send failure stops the relay and this age grows until it is fixed.
     */
    @Bean
    Gauge outboxOldestPendingAgeGauge(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
        return Gauge.builder("outbox.oldest.pending.age.seconds", outbox, repo -> oldestPendingAgeSeconds(repo, clock))
            .baseUnit("seconds")
            .description("Seconds since the oldest unpublished loan event occurred")
            .register(registry);
    }

    static double oldestPendingAgeSeconds(SpringDataOutboxRepository outbox, Clock clock) {
        return outbox.oldestPendingOccurredAt()
            .map(oldest -> (double) Duration.between(oldest, clock.instant()).toSeconds())
            .orElse(0d);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Off unless loan.outbox.relay.enabled=true
     * (OUTBOX_RELAY_ENABLED): it stays off until the aggregate topic evt.ln.loan.v1
     * exists in the platform catalog and on the cluster (runbook step 4).
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "loan.outbox.relay.enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${loan.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${loan.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${loan.outbox.retention:P7D}") Duration retention,
                                MeterRegistry meters) {
            // Failed sends count in outbox.send.failures{exception=<simple class name>}.
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention, meters);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${loan.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${loan.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
