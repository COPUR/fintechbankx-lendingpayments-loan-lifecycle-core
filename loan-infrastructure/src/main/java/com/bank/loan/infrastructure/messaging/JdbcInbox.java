package com.bank.loan.infrastructure.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * inbox_message: which events a consumer group has applied. The row is
 * written in the same transaction as the side effect, so a redelivered event
 * finds it and is skipped, and a failed one leaves no row and is retried.
 */
public class JdbcInbox {

    private final JdbcTemplate jdbc;

    public JdbcInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time the group sees the event */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumerGroup, String eventType, String topic) {
        return jdbc.update("""
            insert into inbox_message (event_id, consumer_group, event_type, topic)
            values (?, ?, ?, ?)
            on conflict (event_id, consumer_group) do nothing
            """, eventId, consumerGroup, eventType, topic) == 1;
    }
}
