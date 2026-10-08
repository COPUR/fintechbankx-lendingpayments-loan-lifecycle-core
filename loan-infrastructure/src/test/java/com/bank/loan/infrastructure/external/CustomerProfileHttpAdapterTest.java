package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.domain.LoanId;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CustomerProfileHttpAdapterTest {

    private static final CustomerId CUSTOMER = CustomerId.of("CUST-HTTP-1");
    private static final LoanId LOAN = LoanId.of("LOAN-HTTP-1");
    private static final String BASE = "http://customer/api/v1/customers/CUST-HTTP-1";
    private static final String POSITION_AED = """
        {"customerId":"CUST-HTTP-1","currency":"AED","creditLimit":50000,"usedCredit":20000,"availableCredit":30000.00}
        """;
    private static final String POSITION_WITHOUT_CURRENCY = """
        {"customerId":"CUST-HTTP-1","creditLimit":50000,"usedCredit":20000,"availableCredit":30000.00}
        """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://customer");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Generations generations = new Generations();
    private final CustomerProfileHttpAdapter adapter =
        new CustomerProfileHttpAdapter(builder.build(), () -> "service-token", Currency.getInstance("AED"), generations, 3);

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void creditPositionIsReadWithTheServiceTokenAndInteractionId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-http");
        server.expect(requestTo(BASE + "/credit"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer service-token"))
            .andExpect(header("x-fapi-interaction-id", "corr-http"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        assertThat(adapter.getAvailableCredit(CUSTOMER)).isEqualTo(Money.aed(new BigDecimal("30000.00")));
        server.verify();
    }

    @Test
    void creditCheckUsesTheCurrencyTheCustomerServiceReports() {
        server.expect(ExpectedCount.times(3), requestTo(BASE + "/credit"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("30000.00")))).isTrue();
        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("30000.01")))).isFalse();
        // credit held in AED, loan in USD: an explicit mismatch, never "insufficient credit"
        assertThatThrownBy(() -> adapter.hasAvailableCredit(CUSTOMER, Money.usd(new BigDecimal("1.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class)
            .hasMessage("The loan is in USD but the customer's credit is held in AED");
        server.verify();
    }

    @Test
    void positionInAnotherCurrencyIsReportedInThatCurrencyAndMismatchesLedgerCurrencyLoans() {
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit"))
            .andRespond(withSuccess(POSITION_AED.replace("\"AED\"", "\"USD\""), MediaType.APPLICATION_JSON));

        assertThat(adapter.getAvailableCredit(CUSTOMER)).isEqualTo(Money.usd(new BigDecimal("30000.00")));
        assertThatThrownBy(() -> adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("10.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class);
    }

    @Test
    void withoutACurrencyInTheResponseTheLedgerCurrencyIsAssumed() {
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit"))
            .andRespond(withSuccess(POSITION_WITHOUT_CURRENCY, MediaType.APPLICATION_JSON));

        assertThat(adapter.hasAvailableCredit(CUSTOMER, Money.aed(new BigDecimal("100.00")))).isTrue();
        assertThatThrownBy(() -> adapter.hasAvailableCredit(CUSTOMER, Money.usd(new BigDecimal("100.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class);
    }

    @Test
    void unknownCustomerIsNotFoundNotNoCredit() {
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit")).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CUSTOMER_NOT_FOUND\"}"));

        assertThatThrownBy(() -> adapter.getAvailableCredit(CUSTOMER)).isInstanceOf(CreditCustomerNotFoundException.class);
        assertThatThrownBy(() -> adapter.hasAvailableCredit(CUSTOMER, Money.aed(BigDecimal.ONE)))
            .isInstanceOf(CreditCustomerNotFoundException.class)
            .hasMessageContaining("CUST-HTTP-1");
    }

    @Test
    void emptyCreditPositionIsUnavailable() {
        server.expect(requestTo(BASE + "/credit")).andRespond(withSuccess());

        assertThatThrownBy(() -> adapter.getAvailableCredit(CUSTOMER)).isInstanceOf(CustomerCreditUnavailableException.class);
    }

    @Test
    void creditPositionOutageIsUnavailableNotAServerError() {
        server.expect(requestTo(BASE + "/credit")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.hasAvailableCredit(CUSTOMER, Money.aed(BigDecimal.ONE)))
            .isInstanceOf(CustomerCreditUnavailableException.class);
    }

    @Test
    void reserveAndReleaseUseKeysDerivedFromTheLoanAndCarryTheLoanAsReference() {
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andExpect(content().json("{\"amount\":2500.00,\"currency\":\"AED\",\"reference\":\"LOAN-HTTP-1\"}"))
            .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:release"))
            .andRespond(withSuccess());

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(new BigDecimal("2500.00")))).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.releaseCredit(LOAN, CUSTOMER, Money.aed(new BigDecimal("2500.00")))).isEqualTo(CreditDecision.ACCEPTED);
        server.verify();
    }

    @Test
    void concurrentUpdateIsRetriedWithTheSameKey() {
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"CONCURRENT_UPDATE\",\"message\":\"retry\"}"));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withSuccess());

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN))).isEqualTo(CreditDecision.ACCEPTED);
        server.verify();
    }

    @Test
    void retriesAreBounded() {
        server.expect(ExpectedCount.times(3), requestTo(BASE + "/credit/reserve"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"DUPLICATE_REQUEST\"}"));

        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class)
            .hasMessageContaining("kept reporting DUPLICATE_REQUEST");
        server.verify();
    }

    @Test
    void onlyInsufficientCreditIsARefusal() {
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INSUFFICIENT_CREDIT\"}"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CUSTOMER_NOT_FOUND\"}"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CURRENCY_MISMATCH\"}"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INVALID_REQUEST\"}"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"SOMETHING_NEW\"}"));

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN))).isEqualTo(CreditDecision.REFUSED);
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CreditCustomerNotFoundException.class);
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CreditCurrencyMismatchException.class).hasMessageContaining("AED");
        // 400 (e.g. a malformed currency, customer #13): loan sent something the provider will never
        // accept; a non-retryable rejection (422 in loan), not "customer service unavailable" (503).
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(com.bank.loan.domain.port.out.CreditMovementRejectedException.class)
            .hasMessageContaining("400 INVALID_REQUEST");
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class).hasMessageContaining("422 SOMETHING_NEW");
        server.verify();
    }

    @Test
    void outagesRejectedTokensAndKeyConflictsAreUnavailable() {
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
        server.expect(requestTo(BASE + "/credit/release")).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class).hasMessageContaining("IDEMPOTENCY_KEY_REUSED");
        assertThatThrownBy(() -> adapter.releaseCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class);
    }

    @Test
    void cancelledReservationIsReleasedUnderItsOwnKeyAndTheNextReservationUsesANewGeneration() {
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andExpect(content().json("{\"amount\":10.00,\"currency\":\"AED\",\"reference\":\"LOAN-HTTP-1\"}"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:g1"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        Money ten = Money.aed(new BigDecimal("10.00"));
        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.cancelReservation(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);
        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);

        assertThat(generations.current(LOAN)).isEqualTo(1);
        server.verify();
    }

    /**
     * The compensation intent (generation n+1, compensation of n pending) is
     * stored before the release is sent. If it cannot be stored, nothing is
     * released: the reservation still stands, so a retry may replay it.
     */
    @Test
    void whenTheCompensationIntentCannotBeStoredNoReleaseIsSentAndTheReservationStands() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        // no release: the next request is the retry's reservation, which replays the held one
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);
        generations.failGenerationWrites = true;
        assertThatThrownBy(() -> adapter.cancelReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        generations.failGenerationWrites = false;
        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);

        assertThat(generations.current(LOAN)).isZero();
        server.verify();
    }

    /**
     * Reverse order: the intent is stored, the release fails. The next
     * reservation first re-sends the pending release under the same key and
     * fails while it cannot; only then does it reserve under a new key. It
     * never replays the compensated reservation's key.
     */
    @Test
    void aFailedReleaseIsResentBeforeTheNextReservationWhichOtherwiseFails() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:g1"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.cancelReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);

        assertThat(generations.current(LOAN)).isEqualTo(1);
        server.verify();
    }

    /**
     * The release succeeded (200) but clearing the intent failed: the next
     * reservation re-sends the release under the same key (the provider
     * replays its stored answer, nothing is released twice) and then reserves
     * under a new key, never the compensated one.
     */
    @Test
    void aReleaseThatSucceededButWasNotRecordedIsReplayedBeforeANewReservation() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:g1"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        generations.failCompensationDone = true;
        assertThat(adapter.cancelReservation(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);
        generations.failCompensationDone = false;
        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);

        server.verify();
    }

    @Test
    void missingOrFailingServiceTokenIsUnavailableAndNothingIsSent() {
        CustomerProfileHttpAdapter noToken =
            new CustomerProfileHttpAdapter(builder.build(), () -> null, Currency.getInstance("AED"), generations, 1);
        CustomerProfileHttpAdapter failingToken = new CustomerProfileHttpAdapter(builder.build(),
            () -> { throw new IllegalStateException("No service token for client registration customer-service"); },
            Currency.getInstance("AED"), generations, 1);

        assertThatThrownBy(() -> noToken.getAvailableCredit(CUSTOMER))
            .isInstanceOf(CustomerCreditUnavailableException.class).hasMessageContaining("No service token");
        assertThatThrownBy(() -> failingToken.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void interactionIdIsGeneratedWhenTheRequestHasNone() {
        server.expect(requestTo(BASE + "/credit"))
            .andExpect(header("x-fapi-interaction-id", org.hamcrest.Matchers.notNullValue()))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        adapter.getAvailableCredit(CUSTOMER);
        server.verify();
    }

    @Test
    void pathsAreConfigurable() {
        CustomerProfileHttpAdapter renamed = new CustomerProfileHttpAdapter(builder.build(), () -> "t",
            Currency.getInstance("AED"), generations, 1, new CustomerProfileHttpAdapter.Paths(
                "/v2/customers/{customerId}/credit", "/v2/customers/{customerId}/reservations",
                "/v2/customers/{customerId}/releases"));
        server.expect(requestTo("http://customer/v2/customers/CUST-HTTP-1/reservations")).andRespond(withSuccess());

        assertThat(renamed.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.ONE))).isEqualTo(CreditDecision.ACCEPTED);
        server.verify();
    }

    @Test
    void unknownCurrencyInTheResponseIsUnavailable() {
        server.expect(requestTo(BASE + "/credit"))
            .andRespond(withSuccess(POSITION_AED.replace("\"AED\"", "\"XYZ1\""), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getAvailableCredit(CUSTOMER)).isInstanceOf(CustomerCreditUnavailableException.class);
    }

    /** In-memory generations with switchable store failures. */
    static final class Generations implements ReservationGenerations {
        private final Map<LoanId, Integer> values = new HashMap<>();
        private final Map<LoanId, Integer> pending = new HashMap<>();
        boolean failGenerationWrites;
        boolean failCompensationDone;

        @Override
        public int current(LoanId loanId) {
            return values.getOrDefault(loanId, 0);
        }


        public void beginCompensation(LoanId loanId, int generation) {
            if (failGenerationWrites) {
                throw new IllegalStateException("database unavailable");
            }
            values.put(loanId, generation + 1);
            pending.put(loanId, generation);
        }

        public java.util.OptionalInt pendingCompensation(LoanId loanId) {
            Integer generation = pending.get(loanId);
            return generation == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(generation);
        }

        public void compensationDone(LoanId loanId, int generation) {
            if (failCompensationDone) {
                throw new IllegalStateException("database unavailable");
            }
            pending.remove(loanId, generation);
        }
    }
}
