package com.bank.loan.infrastructure.outbox;

import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanTerm;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanEventEnvelopeFactoryTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LoanEventEnvelopeFactory factory = new LoanEventEnvelopeFactory(json);

    @Test
    void everyLoanEventMapsToItsContractTypeOnTheAggregateTopic() {
        List<DomainEvent> events = fullLifecycleEvents();

        assertThat(LoanEventEnvelopeFactory.TOPIC).isEqualTo("evt.ln.loan.v1");
        assertThat(events).extracting(e -> factory.toOutboxRow(loanOf(e), e, "corr").getTopic())
            .containsOnly("evt.ln.loan.v1");
        assertThat(events).extracting(e -> LoanEventEnvelopeFactory.map(e).eventType()).containsExactly(
            "Lending.Loan.Created.v1", "Lending.Loan.Approved.v1", "Lending.Loan.Disbursed.v1",
            "Lending.Loan.PaymentMade.v1", "Lending.Loan.FullyPaid.v1");
    }

    @Test
    void paymentMadeCarriesThePaymentIdAndTheScheduleBalances() {
        List<DomainEvent> events = fullLifecycleEvents();
        DomainEvent paymentMade = events.get(3);

        assertThat(LoanEventEnvelopeFactory.map(paymentMade).data())
            .containsKeys("paymentId", "paymentAmount", "previousBalance", "newBalance");
        assertThat(LoanEventEnvelopeFactory.map(paymentMade).data().get("paymentId")).isNotNull();
    }

    @Test
    void rejectedAndCancelledCarryTheReason() {
        Loan rejected = newLoan("LOAN-ENV-R");
        rejected.reject("affordability");
        Loan cancelled = newLoan("LOAN-ENV-C");
        cancelled.cancel("withdrawn");

        assertThat(LoanEventEnvelopeFactory.map(rejected.getDomainEvents().get(1)).data())
            .containsEntry("reason", "affordability");
        assertThat(LoanEventEnvelopeFactory.map(cancelled.getDomainEvents().get(1)).data())
            .containsEntry("reason", "withdrawn");
        assertThat(LoanEventEnvelopeFactory.map(cancelled.getDomainEvents().get(1)).eventType())
            .isEqualTo("Lending.Loan.Cancelled.v1");
    }

    @Test
    void outboxRowCarriesTheStandardEnvelopeWithMoneyAsDecimalStrings() throws Exception {
        Loan loan = newLoan("LOAN-ENV-1");
        loan.setVersion(4L);
        DomainEvent created = loan.getDomainEvents().getFirst();

        OutboxEventJpaEntity row = factory.toOutboxRow(loan, created, "corr-1");
        JsonNode envelope = json.readTree(row.getPayload());

        assertThat(row.getTopic()).isEqualTo("evt.ln.loan.v1");
        assertThat(row.getEventType()).isEqualTo(envelope.get("eventType").asText());
        assertThat(row.getAggregateId()).isEqualTo("LOAN-ENV-1");
        assertThat(row.getEventId().toString()).isEqualTo(created.getEventId());
        assertThat(envelope.get("eventId").asText()).isEqualTo(created.getEventId());
        assertThat(envelope.get("eventType").asText()).isEqualTo("Lending.Loan.Created.v1");
        assertThat(envelope.get("aggregateVersion").asLong()).isEqualTo(4L);
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-ln-loan-lifecycle");
        assertThat(envelope.get("occurredAt").asText()).isEqualTo(created.getOccurredOn().toString());
        assertThat(envelope.at("/data/principalAmount/amount").isTextual()).isTrue();
        assertThat(envelope.at("/data/principalAmount/amount").asText()).isEqualTo("15000.00");
        assertThat(envelope.at("/data/customerId").asText()).isEqualTo("CUST-ENV");
    }

    @Test
    void unknownEventsAreRefusedRatherThanPublishedWithoutAContract() {
        DomainEvent unknown = new DomainEvent() { };

        assertThatThrownBy(() -> LoanEventEnvelopeFactory.map(unknown))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No public contract");
    }

    private static Loan loanOf(DomainEvent event) {
        return newLoan("LOAN-ENV-2");
    }

    private static List<DomainEvent> fullLifecycleEvents() {
        Loan loan = newLoan("LOAN-ENV-2");
        loan.approve();
        loan.disburse();
        loan.makePayment(loan.getOutstandingBalance());
        return new ArrayList<>(loan.getDomainEvents());
    }

    private static Loan newLoan(String id) {
        return Loan.create(LoanId.of(id), CustomerId.of("CUST-ENV"), Money.aed(new BigDecimal("15000.00")),
            InterestRate.of(new BigDecimal("7.25")), LoanTerm.ofMonths(24));
    }
}
