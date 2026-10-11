package com.bank.loan.infrastructure.outbox;

import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class OutboxLoanEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxLoanEventPublisher publisher =
        new OutboxLoanEventPublisher(outbox, new LoanEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void rowsCarryTheRequestsCorrelationIdAndTraceparent() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-pub");
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        Loan loan = loan();

        publisher.publish(loan, List.copyOf(loan.getDomainEvents()));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getCorrelationId()).isEqualTo("corr-pub");
            assertThat(row.getFapiInteractionId()).isEqualTo("corr-pub");
            assertThat(row.getTraceparent()).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        });
    }

    @Test
    void outsideARequestANewCorrelationIdIsUsedAndNoTraceparent() {
        MDC.put("traceId", "not-hex");
        MDC.put("spanId", "b7ad6b7169203331");
        Loan loan = loan();

        publisher.publish(loan, List.copyOf(loan.getDomainEvents()));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().getCorrelationId()).hasSize(36);
        // Not started at the FAPI API (consumer, sweep): no interaction id is invented.
        assertThat(rows.getValue().getFirst().getFapiInteractionId()).isNull();
        assertThat(rows.getValue().getFirst().getTraceparent()).isNull();
        assertThat(OutboxLoanEventPublisher.currentTraceparent()).isNull();
    }

    /** ADR-019 section 4: events caused by a consumed message carry its correlationId and eventId. */
    @Test
    void eventsCausedByAConsumedMessageCarryItsCorrelationAndCausation() throws Exception {
        Loan loan = loan();

        publisher.publish(loan, List.copyOf(loan.getDomainEvents()),
            new com.bank.loan.domain.port.out.EventCausation("corr-pay-1", "6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11"));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        OutboxEventJpaEntity row = rows.getValue().getFirst();
        assertThat(row.getCorrelationId()).isEqualTo("corr-pay-1");
        assertThat(row.getFapiInteractionId()).isNull();
        com.fasterxml.jackson.databind.JsonNode envelope = new ObjectMapper().readTree(row.getPayload());
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-pay-1");
        assertThat(envelope.get("causationId").asText()).isEqualTo("6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11");
    }

    /** A flow that starts at the API has no cause: causationId stays null, as before. */
    @Test
    void eventsThatStartAFlowHaveNoCausation() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-api");
        Loan loan = loan();

        publisher.publish(loan, List.copyOf(loan.getDomainEvents()));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(new ObjectMapper().readTree(rows.getValue().getFirst().getPayload()).get("causationId").isNull()).isTrue();
    }

    private static Loan loan() {
        return Loan.create(LoanId.of("LOAN-PUB"), CustomerId.of("CUST-PUB"), Money.aed(new BigDecimal("6000.00")),
            InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(6));
    }
}
