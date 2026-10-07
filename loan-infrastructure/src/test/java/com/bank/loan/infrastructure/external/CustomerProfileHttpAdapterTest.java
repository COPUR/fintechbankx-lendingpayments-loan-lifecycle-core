package com.bank.loan.infrastructure.external;

import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CustomerProfileHttpAdapterTest {

    private static final CustomerId CUSTOMER = CustomerId.of("CUST-HTTP-1");
    private static final String CUSTOMER_JSON = """
        {"customerId":"CUST-HTTP-1","firstName":"Never","lastName":"Stored","email":"x@example.com",
         "creditLimit":50000,"usedCredit":20000,"availableCredit":30000.00,"status":"ACTIVE"}
        """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://customer");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CustomerProfileHttpAdapter adapter =
        new CustomerProfileHttpAdapter(builder.build(), () -> "caller-token", Currency.getInstance("AED"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void availableCreditIsReadFromTheCustomerServiceWithCallerTokenAndInteractionId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-http");
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer caller-token"))
            .andExpect(header("x-fapi-interaction-id", "corr-http"))
            .andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));

        Money available = adapter.getAvailableCredit(CUSTOMER);

        assertThat(available).isEqualTo(Money.aed(new BigDecimal("30000.00")));
        server.verify();
    }

    @Test
    void creditCheckComparesInTheLedgerCurrencyOnly() {
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1"))
            .andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1"))
            .andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));

        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("30000.00")))).isTrue();
        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("30000.01")))).isFalse();
        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.usd(new BigDecimal("1.00")))).isFalse();
        server.verify();
    }

    @Test
    void unknownCustomerHasNoCredit() {
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.getAvailableCredit(CUSTOMER)).isEqualTo(Money.zero(Currency.getInstance("AED")));
    }

    @Test
    void reserveAndReleasePostTheMovementWithAnIdempotencyKey() {
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1/credit/reserve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-idempotency-key", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
            .andExpect(content().json("{\"amount\":2500.00,\"currency\":\"AED\"}"))
            .andRespond(withSuccess());
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1/credit/release"))
            .andRespond(withSuccess());

        assertThat(adapter.reserveCredit(CUSTOMER, Money.aed(new BigDecimal("2500.00")))).isTrue();
        assertThat(adapter.releaseCredit(CUSTOMER, Money.aed(new BigDecimal("2500.00")))).isTrue();
        server.verify();
    }

    @Test
    void businessRefusalsReturnFalseButOutagesPropagate() {
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1/credit/reserve"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1/credit/reserve"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(adapter.reserveCredit(CUSTOMER, Money.aed(BigDecimal.TEN))).isFalse();
        assertThatThrownBy(() -> adapter.reserveCredit(CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void callsWithoutAnAuthenticatedCallerSendNoAuthorizationHeader() {
        CustomerProfileHttpAdapter anonymous =
            new CustomerProfileHttpAdapter(builder.build(), () -> null, Currency.getInstance("AED"));
        server.expect(requestTo("http://customer/api/v1/customers/CUST-HTTP-1"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(header("x-fapi-interaction-id", org.hamcrest.Matchers.notNullValue()))
            .andRespond(withSuccess(CUSTOMER_JSON, MediaType.APPLICATION_JSON));

        anonymous.getAvailableCredit(CUSTOMER);
        server.verify();
    }
}
