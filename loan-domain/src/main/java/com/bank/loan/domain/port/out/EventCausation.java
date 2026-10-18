package com.bank.loan.domain.port.out;

import java.util.Objects;

/**
 * Which message caused a change, for the events the change raises (ADR-019
 * section 4): {@code correlationId} is the business flow's id, propagated end
 * to end; {@code causationId} is the eventId (or command id) of the message
 * that caused the events, null when they start a flow. Plain ids: no
 * messaging types cross the ports.
 */
public record EventCausation(String correlationId, String causationId) {

    public EventCausation {
        Objects.requireNonNull(correlationId, "correlationId");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (causationId != null && causationId.isBlank()) {
            causationId = null;
        }
    }
}
