package com.bank.loan.infrastructure.outbox;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanApprovedEvent;
import com.bank.loan.domain.LoanCancelledEvent;
import com.bank.loan.domain.LoanCreatedEvent;
import com.bank.loan.domain.LoanDisbursedEvent;
import com.bank.loan.domain.LoanFullyPaidEvent;
import com.bank.loan.domain.LoanPaymentMadeEvent;
import com.bank.loan.domain.LoanRejectedEvent;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Loan domain events into the public envelope of the AsyncAPI contract
 * svc-ln-loan-lifecycle.yaml (asyncapi catalog): topic evt.ln.loan.&lt;event&gt;.v1,
 * eventType Lending.Loan.&lt;Event&gt;.v1, money as decimal strings, ids only.
 */
public class LoanEventEnvelopeFactory {

    public static final String PRODUCER = "svc-ln-loan-lifecycle";
    public static final String AGGREGATE_TYPE = "Loan";

    private final ObjectMapper objectMapper;

    public LoanEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(Loan loan, DomainEvent event, String correlationId) {
        PublicEvent mapped = map(event);
        UUID eventId = UUID.fromString(event.getEventId());
        String aggregateId = loan.getId().getValue();
        long aggregateVersion = loan.getVersion() == null ? 0L : loan.getVersion();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.getOccurredOn().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(eventId, AGGREGATE_TYPE, aggregateId, aggregateVersion,
            mapped.eventType(), mapped.topic(), toJson(envelope), correlationId, event.getOccurredOn());
    }

    static PublicEvent map(DomainEvent event) {
        return switch (event) {
            case LoanCreatedEvent e -> new PublicEvent("created", "Created", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "principalAmount", money(e.getPrincipalAmount())));
            case LoanApprovedEvent e -> new PublicEvent("approved", "Approved", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "principalAmount", money(e.getPrincipalAmount())));
            case LoanRejectedEvent e -> new PublicEvent("rejected", "Rejected", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "reason", e.getReason()));
            case LoanDisbursedEvent e -> new PublicEvent("disbursed", "Disbursed", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "principalAmount", money(e.getPrincipalAmount()),
                "disbursementDate", e.getDisbursementDate().toString()));
            case LoanCancelledEvent e -> new PublicEvent("cancelled", "Cancelled", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "reason", e.getReason()));
            case LoanPaymentMadeEvent e -> new PublicEvent("payment-made", "PaymentMade", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue(),
                "paymentAmount", money(e.getPaymentAmount()),
                "previousBalance", money(e.getPreviousBalance()),
                "newBalance", money(e.getNewBalance())));
            case LoanFullyPaidEvent e -> new PublicEvent("fully-paid", "FullyPaid", data(
                "loanId", e.getLoanId().getValue(),
                "customerId", e.getCustomerId().getValue()));
            default -> throw new IllegalArgumentException(
                "No public contract for loan event " + event.getClass().getName());
        };
    }

    private static Map<String, Object> money(Money money) {
        return data("amount", money.getAmount().toPlainString(), "currency", money.getCurrency().getCurrencyCode());
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise loan event envelope", e);
        }
    }

    record PublicEvent(String topicSuffix, String eventName, Map<String, Object> data) {
        String topic() {
            return "evt.ln.loan." + topicSuffix + ".v1";
        }

        String eventType() {
            return "Lending.Loan." + eventName + ".v1";
        }
    }
}
