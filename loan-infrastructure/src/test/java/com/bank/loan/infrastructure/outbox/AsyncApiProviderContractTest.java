package com.bank.loan.infrastructure.outbox;

import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.infrastructure.messaging.RepaymentConsumerConfiguration;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider contract: every event LoanEventEnvelopeFactory can produce is a
 * message of the one aggregate channel evt.ln.loan.v1 in
 * api/asyncapi/svc-ln-loan-lifecycle.yaml (ADR-019, one topic per aggregate),
 * with the same eventType const in the payload and in the eventType record
 * header, the common envelope and the same data fields; and the DLQ, the
 * consumed topic, the consumed event type and the consumer group in code
 * match the spec.
 */
class AsyncApiProviderContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void load() throws Exception {
        Path file = Path.of("..", "api", "asyncapi", "svc-ln-loan-lifecycle.yaml");
        try (Reader reader = Files.newBufferedReader(file)) {
            spec = new Yaml().load(reader);
        }
    }

    @Test
    void everyPublishedEventIsAMessageOfTheAggregateChannel() {
        Map<String, Object> channels = map(spec.get("channels"));
        Map<String, Object> loan = map(channels.get("loan"));
        assertThat(loan.get("address")).isEqualTo(LoanEventEnvelopeFactory.TOPIC);
        assertThat(map(map(loan.get("bindings")).get("kafka")).get("topic")).isEqualTo(LoanEventEnvelopeFactory.TOPIC);
        Set<String> seen = new java.util.HashSet<>();

        for (DomainEvent event : everyKindOfEvent()) {
            LoanEventEnvelopeFactory.PublicEvent mapped = LoanEventEnvelopeFactory.map(event);
            String name = mapped.eventType().replace("Lending.Loan.", "").replace(".v1", "");
            seen.add(name);

            assertThat(map(loan.get("messages"))).as(name + " on " + LoanEventEnvelopeFactory.TOPIC)
                .containsKey("Loan" + name);
            Map<String, Object> message = map(map(map(spec.get("components")).get("messages")).get("Loan" + name));
            assertThat(message.get("title")).as(name + " title").isEqualTo(mapped.eventType());
            assertThat(eventTypeConst(message.get("payload"))).as(name + " payload eventType").isEqualTo(mapped.eventType());
            assertThat(eventTypeConst(message.get("headers"))).as(name + " eventType header").isEqualTo(mapped.eventType());
            assertThat(String.valueOf(message.get("payload"))).contains("#/components/schemas/EventEnvelope");
            assertThat(String.valueOf(message.get("headers"))).contains("#/components/schemas/EventHeaders");

            Map<String, Object> schema = map(map(map(spec.get("components")).get("schemas")).get("Loan" + name + "Data"));
            assertThat(map(schema.get("properties")).keySet()).as(name + " data fields").isEqualTo(mapped.data().keySet());
            List<?> required = (List<?>) schema.get("required");
            Set<String> present = mapped.data().entrySet().stream().filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
            assertThat(present).as(name + " required fields present").containsAll(required.stream().map(String::valueOf).toList());
        }
        assertThat(seen).containsExactlyInAnyOrder("Created", "Approved", "Rejected", "Disbursed", "Cancelled",
            "PaymentMade", "FullyPaid");
        assertThat(map(loan.get("messages"))).hasSize(seen.size());
    }

    @Test
    void theCommonEnvelopeAndHeadersAreReferenced() {
        Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
        assertThat(map(schemas.get("EventEnvelope")).get("$ref")).isEqualTo("./common/event-envelope.yaml#/EventEnvelope");
        assertThat(map(schemas.get("EventHeaders")).get("$ref")).isEqualTo("./common/event-envelope.yaml#/EventHeaders");
        assertThat(map(schemas.get("DeadLetterHeaders")).get("$ref")).isEqualTo("./common/event-envelope.yaml#/DeadLetterHeaders");
        assertThat(Path.of("..", "api", "asyncapi", "common", "event-envelope.yaml")).exists();
    }

    @Test
    void deadLetterTopicMatchesTheCodeAndUsesTheCommonDeadLetterHeaders() {
        Map<String, Object> channels = map(spec.get("channels"));
        Map<String, Object> dlq = map(channels.get("loanDeadLetter"));
        assertThat(dlq.get("address")).isEqualTo(RepaymentConsumerConfiguration.DLQ_TOPIC);
        Map<String, Object> deadLetter = map(map(map(spec.get("components")).get("messages")).get("DeadLetter"));
        assertThat(map(deadLetter.get("headers")).get("$ref")).isEqualTo("#/components/schemas/DeadLetterHeaders");
    }

    @Test
    void consumedTopicEventTypeAndGroupMatchTheCode() {
        Map<String, Object> channels = map(spec.get("channels"));
        Map<String, Object> consumed = map(channels.get("payment"));
        assertThat(consumed.get("address")).isEqualTo("evt.pay.payment.v1");
        Map<String, Object> receive = map(map(spec.get("operations")).get("receivePaymentLoanPaymentCompleted"));
        assertThat(receive.get("action")).isEqualTo("receive");
        assertThat(String.valueOf(receive)).contains(RepaymentConsumerConfiguration.CONSUMER_GROUP);
        // Only the one event type the consumer handles; it skips the payment aggregate's other types.
        assertThat(map(consumed.get("messages"))).containsOnlyKeys("PaymentLoanPaymentCompleted");
        Map<String, Object> message = map(map(map(spec.get("components")).get("messages")).get("PaymentLoanPaymentCompleted"));
        assertThat(eventTypeConst(message.get("payload"))).isEqualTo("Payments.Payment.LoanPaymentCompleted.v1");
        assertThat(eventTypeConst(message.get("headers"))).isEqualTo("Payments.Payment.LoanPaymentCompleted.v1");
    }

    /** The eventType const of the second allOf part (after the common envelope or headers $ref). */
    private static Object eventTypeConst(Object schema) {
        List<?> allOf = (List<?>) map(schema).get("allOf");
        assertThat(allOf).hasSize(2);
        return map(map(map(allOf.get(1)).get("properties")).get("eventType")).get("const");
    }

    private static List<DomainEvent> everyKindOfEvent() {
        List<DomainEvent> events = new ArrayList<>();
        Loan paid = Loan.create(LoanId.of("LOAN-CONTRACT-1"), CustomerId.of("CUST-12345678"),
            Money.aed(new BigDecimal("1000.00")), InterestRate.zero(), LoanTerm.ofMonths(3));
        paid.approve();
        paid.disburse();
        paid.makePayment(PaymentId.of("PAY-CONTRACT-1"), Money.aed(new BigDecimal("1000.00")));
        events.addAll(paid.getDomainEvents());
        Loan rejected = Loan.create(LoanId.of("LOAN-CONTRACT-2"), CustomerId.of("CUST-12345678"),
            Money.aed(new BigDecimal("1000.00")), InterestRate.zero(), LoanTerm.ofMonths(3));
        rejected.reject("policy");
        events.addAll(rejected.getDomainEvents());
        Loan cancelled = Loan.create(LoanId.of("LOAN-CONTRACT-3"), CustomerId.of("CUST-12345678"),
            Money.aed(new BigDecimal("1000.00")), InterestRate.zero(), LoanTerm.ofMonths(3));
        cancelled.cancel(null);
        events.addAll(cancelled.getDomainEvents());
        return events;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
