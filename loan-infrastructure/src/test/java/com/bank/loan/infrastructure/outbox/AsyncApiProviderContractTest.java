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
 * Provider contract: every event LoanEventEnvelopeFactory can produce has a
 * channel in api/asyncapi/svc-ln-loan-lifecycle.yaml with the same topic,
 * eventType and data fields, and the DLQ and consumed topics in code match
 * the spec.
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
    void everyPublishedEventMatchesItsChannelAndSchema() {
        List<DomainEvent> events = everyKindOfEvent();
        Set<String> seen = new java.util.HashSet<>();

        for (DomainEvent event : events) {
            LoanEventEnvelopeFactory.PublicEvent mapped = LoanEventEnvelopeFactory.map(event);
            String name = mapped.eventType().replace("Lending.Loan.", "").replace(".v1", "");
            seen.add(name);

            Map<String, Object> channel = map(map(spec.get("channels")).get("loan" + name));
            assertThat(channel.get("address")).as(name + " topic").isEqualTo(mapped.topic());
            Map<String, Object> message = map(map(map(spec.get("components")).get("messages")).get("Loan" + name));
            assertThat(message.get("name")).as(name + " eventType").isEqualTo(mapped.eventType());

            Map<String, Object> schema = map(map(map(spec.get("components")).get("schemas")).get("Loan" + name + "Data"));
            assertThat(map(schema.get("properties")).keySet()).as(name + " data fields").isEqualTo(mapped.data().keySet());
            List<?> required = (List<?>) schema.get("required");
            Set<String> present = mapped.data().entrySet().stream().filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
            assertThat(present).as(name + " required fields present").containsAll(required.stream().map(String::valueOf).toList());
        }
        assertThat(seen).containsExactlyInAnyOrder("Created", "Approved", "Rejected", "Disbursed", "Cancelled",
            "PaymentMade", "FullyPaid");
    }

    @Test
    void deadLetterAndConsumedTopicsMatchTheCode() {
        Map<String, Object> channels = map(spec.get("channels"));
        assertThat(map(channels.get("loanDeadLetter")).get("address")).isEqualTo(RepaymentConsumerConfiguration.DLQ_TOPIC);
        assertThat(map(channels.get("loanPaymentCompleted")).get("address"))
            .isEqualTo("evt.pay.payment.loan-payment-completed.v1");
        assertThat(String.valueOf(map(map(spec.get("operations")).get("receiveLoanPaymentCompleted"))))
            .contains(RepaymentConsumerConfiguration.CONSUMER_GROUP);
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
