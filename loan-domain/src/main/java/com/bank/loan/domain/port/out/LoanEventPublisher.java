package com.bank.loan.domain.port.out;

import com.bank.loan.domain.Loan;
import com.bank.shared.kernel.domain.DomainEvent;

import java.util.List;

/**
 * Outbound port for the loan's domain events.
 *
 * Implementations must record the events in the same transaction as the
 * aggregate (transactional outbox), never publish to a broker directly.
 */
public interface LoanEventPublisher {

    /**
     * Events of a change that started a flow here (a loan API request, the
     * recovery sweep): the implementation takes the request's correlation id,
     * or a new one, and no causation.
     */
    default void publish(Loan loan, List<DomainEvent> events) {
        publish(loan, events, null);
    }

    /**
     * Events of a change caused by another message (ADR-019 section 4): they
     * carry its correlationId and causationId. {@code causation} null means
     * the change started a flow, as {@link #publish(Loan, List)}.
     */
    void publish(Loan loan, List<DomainEvent> events, EventCausation causation);
}
