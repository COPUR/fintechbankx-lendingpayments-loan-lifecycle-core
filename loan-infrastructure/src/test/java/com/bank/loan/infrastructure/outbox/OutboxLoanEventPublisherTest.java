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
        assertThat(rows.getValue().getFirst().getTraceparent()).isNull();
        assertThat(OutboxLoanEventPublisher.currentTraceparent()).isNull();
    }

    private static Loan loan() {
        return Loan.create(LoanId.of("LOAN-PUB"), CustomerId.of("CUST-PUB"), Money.aed(new BigDecimal("6000.00")),
            InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(6));
    }
}
