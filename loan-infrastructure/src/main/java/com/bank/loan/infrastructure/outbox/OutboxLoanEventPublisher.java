package com.bank.loan.infrastructure.outbox;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.port.out.LoanEventPublisher;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.DomainEvent;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the loan row and its events commit or roll
 * back together. {@link OutboxRelay} ships them to Kafka afterwards.
 */
@Component
public class OutboxLoanEventPublisher implements LoanEventPublisher {

    private final SpringDataOutboxRepository outbox;
    private final LoanEventEnvelopeFactory envelopes;

    public OutboxLoanEventPublisher(SpringDataOutboxRepository outbox, LoanEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Loan loan, List<DomainEvent> events) {
        String correlationId = currentCorrelationId();
        outbox.saveAll(events.stream()
            .map(event -> envelopes.toOutboxRow(loan, event, correlationId))
            .toList());
    }

    private static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
