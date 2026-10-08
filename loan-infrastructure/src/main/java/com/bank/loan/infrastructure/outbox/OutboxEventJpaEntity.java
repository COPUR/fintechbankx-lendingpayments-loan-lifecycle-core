package com.bank.loan.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_ln_loan_lifecycle.outbox_event: one envelope waiting to be
 * relayed to Kafka. Written in the aggregate's transaction.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 249, updatable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 128, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "parked_at")
    private Instant parkedAt;

    /** Why the row was parked: "payload error: ..." from the relay, or the operator's reason (V7). */
    @Column(name = "park_reason", length = 512)
    private String parkReason;

    /** True once this park was counted in outbox_parked_events_total (V8). */
    @Column(name = "park_counted", nullable = false)
    private boolean parkCounted;

    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
                                String eventType, String topic, String payload, String correlationId,
                                Instant occurredAt) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public OutboxEventJpaEntity withTraceparent(String traceparent) {
        this.traceparent = traceparent;
        return this;
    }

    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public long getAggregateVersion() { return aggregateVersion; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getParkedAt() { return parkedAt; }
    public String getTraceparent() { return traceparent; }
    public String getParkReason() { return parkReason; }
    public boolean isParkCounted() { return parkCounted; }

    void markPublished(Instant at) {
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    void markFailed(String error) {
        this.attempts++;
        this.lastError = truncate(error);
    }

    /** Takes the row out of the relay; only payload errors do this automatically (ADR-021 decision 4). */
    void park(Instant at, String reason) {
        this.parkedAt = at;
        this.parkReason = truncate(reason);
        this.parkCounted = true;
    }

    /** An operator park (runbook SQL) has been counted. */
    void markParkCounted() {
        this.parkCounted = true;
    }

    private static String truncate(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 512));
    }
}
