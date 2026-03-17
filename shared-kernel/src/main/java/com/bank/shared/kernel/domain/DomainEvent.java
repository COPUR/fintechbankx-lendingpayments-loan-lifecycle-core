package com.bank.shared.kernel.domain;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    default String getEventId() {
        return UUID.randomUUID().toString();
    }

    default Instant getOccurredOn() {
        return Instant.now();
    }

    default String getEventType() {
        return getClass().getSimpleName();
    }
}
