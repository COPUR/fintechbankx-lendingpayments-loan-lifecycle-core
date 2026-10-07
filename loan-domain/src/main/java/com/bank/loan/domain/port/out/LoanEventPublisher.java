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

    void publish(Loan loan, List<DomainEvent> events);
}
