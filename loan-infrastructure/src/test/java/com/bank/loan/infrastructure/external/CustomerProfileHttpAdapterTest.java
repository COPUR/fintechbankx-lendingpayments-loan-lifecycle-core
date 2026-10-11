package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CreditReservationNeedsOperatorException;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.loan.domain.port.out.CustomerCreditService.UnusedReservation;
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
            .message().doesNotContain("CUST-HTTP-1");
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
            .hasMessageContaining("kept reporting DUPLICATE_REQUEST")
            .message().doesNotContain("LOAN-HTTP-1");   // the idempotency key carries the loan id
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
            .hasMessageContaining("400 INVALID_REQUEST")
            .message().doesNotContain("LOAN-HTTP-1");
        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(BigDecimal.TEN)))
            .isInstanceOf(CustomerCreditUnavailableException.class).hasMessageContaining("422 SOMETHING_NEW")
            .message().doesNotContain("LOAN-HTTP-1");
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

    // --- review 5460235552: reservations are recorded; only accepted ones are released ---------------

    @Test
    void aReservationIsRecordedBeforeItIsSentAndMarkedReservedOnceAccepted() {
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(request -> {
                assertThat(generations.state(LOAN)).isEqualTo(ReservationGenerations.State.RESERVING);
                return withSuccess(POSITION_AED, MediaType.APPLICATION_JSON).createResponse(request);
            });

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(new BigDecimal("10.00")))).isEqualTo(CreditDecision.ACCEPTED);

        assertThat(generations.state(LOAN)).isEqualTo(ReservationGenerations.State.RESERVED);
        server.verify();
    }

    @Test
    void whenTheReservationCannotBeRecordedNothingIsReserved() {
        generations.failReservationWrites = true;

        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(new BigDecimal("10.00"))))
            .isInstanceOf(CustomerCreditUnavailableException.class)
            .hasMessageContaining("nothing reserved");
        server.verify();
    }

    @Test
    void anAnswerThatIsNotRecordedStillCountsAndLeavesTheRowReserving() {
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        generations.failAnswers = true;

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, Money.aed(new BigDecimal("10.00")))).isEqualTo(CreditDecision.ACCEPTED);

        assertThat(generations.state(LOAN)).isEqualTo(ReservationGenerations.State.RESERVING);
    }

    @Test
    void aRefusedReservationLeavesNothingToRelease() {
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INSUFFICIENT_CREDIT\",\"message\":\"no\"}"));
        Money ten = Money.aed(new BigDecimal("10.00"));

        assertThat(adapter.reserveCredit(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.REFUSED);

        assertThat(generations.state(LOAN)).isNull();
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.NONE);
        server.verify();
    }

    /**
     * The reserve timed out: the customer service may or may not have applied
     * it, and its release subtracts without checking (customer #13
     * CreditProfile.releaseCredit). Nothing is released.
     */
    @Test
    void aReservationWithoutAnAnswerIsNeverReleasedBlind() {
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andRespond(withException(new SocketTimeoutException("Read timed out")));
        Money ten = Money.aed(new BigDecimal("10.00"));

        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);

        assertThat(generations.state(LOAN)).isEqualTo(ReservationGenerations.State.RESERVING);
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.UNCONFIRMED);
        server.verify();
    }

    /**
     * The sweep's release of a reserve that was never answered (customer CRC
     * decision 2026-10-10): once the sweep has recorded the intent, the
     * release goes under the reserve's compensation key with the loan id as
     * reference, the same reference the reserve carried. 422
     * RESERVATION_NOT_FOUND means the reserve was never applied: the row is
     * cleared and the release counted as unmatched.
     */
    @Test
    void anUnansweredReserveIsReleasedByItsReferenceOnceTheSweepRecordedTheIntent() {
        Money amount = Money.aed(new BigDecimal("12000.00"));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andExpect(content().json("{\"amount\":12000.00,\"currency\":\"AED\",\"reference\":\"LOAN-HTTP-1\"}"))
            .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andExpect(content().json("{\"amount\":12000.00,\"currency\":\"AED\",\"reference\":\"LOAN-HTTP-1\"}"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"RESERVATION_NOT_FOUND\",\"message\":\"no reservation for the reference\"}"));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        CustomerProfileHttpAdapter counted = new CustomerProfileHttpAdapter(builder.build(), () -> "service-token",
            Currency.getInstance("AED"), generations, 3, CustomerProfileHttpAdapter.Paths.DEFAULT, meters);

        assertThatThrownBy(() -> counted.reserveCredit(LOAN, CUSTOMER, amount))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThat(generations.beginCompensationOfUnanswered(LOAN, 0, java.time.Instant.parse("2026-10-10T00:00:00Z"))).isTrue();
        assertThat(counted.releaseUnusedReservation(LOAN, CUSTOMER, amount)).isEqualTo(UnusedReservation.NONE);

        assertThat(generations.current(LOAN)).isEqualTo(1);
        assertThat(generations.pendingCompensation(LOAN)).isEmpty();
        assertThat(generations.state(LOAN)).isNull();
        assertThat(meters.get("loan.credit.releases.unmatched").counter().count()).isEqualTo(1.0);
        server.verify();
    }

    @Test
    void anAcceptedUnusedReservationIsReleasedOnceUnderItsCompensationKey() {
        Money amount = Money.aed(new BigDecimal("25000.00"));
        server.expect(requestTo(BASE + "/credit/reserve"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andExpect(content().json("{\"amount\":25000.00,\"currency\":\"AED\",\"reference\":\"LOAN-HTTP-1\"}"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        adapter.reserveCredit(LOAN, CUSTOMER, amount);
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, amount)).isEqualTo(UnusedReservation.RELEASED);
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, amount)).isEqualTo(UnusedReservation.NONE);

        assertThat(generations.current(LOAN)).isEqualTo(1);
        assertThat(generations.countPending()).isZero();
        server.verify();
    }

    @Test
    void anUnconfirmedReleaseStaysPendingAndIsResentUnderTheSameKey() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThat(generations.pendingCompensation(LOAN)).hasValue(0);
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.RELEASED);

        assertThat(generations.pendingCompensation(LOAN)).isEmpty();
        server.verify();
    }

    @Test
    void aRefusedReleaseOfAnUnusedReservationIsUnavailable() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit/release"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INSUFFICIENT_CREDIT\",\"message\":\"no\"}"));

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        server.verify();
    }

    @Test
    void theDisbursementCanUseAReservationOnlyWhileItIsOutstanding() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(ExpectedCount.times(2), requestTo(BASE + "/credit/reserve"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        LoanId other = LoanId.of("LOAN-HTTP-2");

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        adapter.markReservationUsed(LOAN);
        assertThat(generations.state(LOAN)).isEqualTo(ReservationGenerations.State.USED);
        assertThat(adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.NONE);

        adapter.reserveCredit(other, CUSTOMER, ten);
        adapter.releaseUnusedReservation(other, CUSTOMER, ten);
        assertThatThrownBy(() -> adapter.markReservationUsed(other))
            .isInstanceOf(CustomerCreditUnavailableException.class)
            .hasMessageContaining("released meanwhile");
    }

    @Test
    void aReservationAlreadyReleasedByTheCancellationIsNotCancelledAgain() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten);
        // the failed disbursement's own compensation comes second: generation 1 was never reserved
        assertThat(adapter.cancelReservation(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);

        server.verify();
    }

    // --- customer release-by-reference (provider contract pending) -----------------------------------

    /**
     * 422 RESERVATION_NOT_FOUND: nothing is held under the loan's reference,
     * because nothing was reserved or because the reservation already holds 0
     * (customer CRC, round 5: a zero-holding reservation answers
     * RESERVATION_NOT_FOUND, not RELEASE_EXCEEDS_RESERVATION). Either way the
     * reservation counts as already released: the intent is done, the row is
     * cleared, the release counted as unmatched, and nothing waits for an
     * operator. The sweep's release-by-reference goes through this same path
     * (CreditReservationSweep counts the outcome as nothingHeld).
     */
    @Test
    void aReleaseOfAReservationTheCustomerServiceDoesNotHoldCompletesTheIntent() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andExpect(header("x-idempotency-key", "LOAN-HTTP-1:reserve:compensation"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"RESERVATION_NOT_FOUND\",\"message\":\"no reservation for the reference\"}"));

        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        CustomerProfileHttpAdapter counted = new CustomerProfileHttpAdapter(builder.build(), () -> "service-token",
            Currency.getInstance("AED"), generations, 3, CustomerProfileHttpAdapter.Paths.DEFAULT, meters);

        counted.reserveCredit(LOAN, CUSTOMER, ten);
        assertThat(counted.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.NONE);
        assertThat(counted.releaseUnusedReservation(LOAN, CUSTOMER, ten)).isEqualTo(UnusedReservation.NONE);

        assertThat(generations.pendingCompensation(LOAN)).isEmpty();
        assertThat(meters.get("loan.credit.releases.unmatched").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("loan.credit.releases.unmatched").counter().getId().getTags()).isEmpty();
        server.verify();
    }

    /**
     * 422 RELEASE_EXCEEDS_RESERVATION is a bug signal: the row is left for an
     * operator, nothing is re-sent. Review minor (sendCompensation): from then
     * on every caller, including a disbursement of the loan, gets the
     * non-retryable {@link CreditReservationNeedsOperatorException}, not the
     * retryable "customer service unavailable".
     */
    @Test
    void aReleaseExceedingTheReservationIsLeftForAnOperator() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"RELEASE_EXCEEDS_RESERVATION\",\"message\":\"more than reserved\"}"));

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CreditReservationNeedsOperatorException.class)
            .isNotInstanceOf(CustomerCreditUnavailableException.class)
            .hasMessageContaining("RELEASE_EXCEEDS_RESERVATION");
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CreditReservationNeedsOperatorException.class)
            .hasMessageContaining("operator");

        assertThat(generations.pendingCompensation(LOAN)).hasValue(0);
        assertThat(generations.releaseRefusal(LOAN)).isEqualTo("RELEASE_EXCEEDS_RESERVATION");
        server.verify();
    }

    /** The parked loan's next disbursement: no request is sent, and the answer is not "retry later". */
    @Test
    void aDisbursementOfALoanHeldForAnOperatorIsRefusedWithoutARequest() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        generations.beginReservation(LOAN, 0);
        generations.reservationAnswered(LOAN, 0, true);
        generations.beginCompensation(LOAN, 0);
        generations.releaseRefused(LOAN, 0, "RELEASE_EXCEEDS_RESERVATION");

        assertThatThrownBy(() -> adapter.reserveCredit(LOAN, CUSTOMER, ten))
            .isInstanceOf(CreditReservationNeedsOperatorException.class)
            .isNotInstanceOf(CustomerCreditUnavailableException.class)
            .satisfies(held -> assertThat(((CreditReservationNeedsOperatorException) held).getReason())
                .isEqualTo("RELEASE_EXCEEDS_RESERVATION"));
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CreditReservationNeedsOperatorException.class);
        // the cancellation of the failed disbursement finds nothing outstanding at the new generation
        assertThat(adapter.cancelReservation(LOAN, CUSTOMER, ten)).isEqualTo(CreditDecision.ACCEPTED);
        server.verify();
    }

    /** If the refusal could not be recorded the row is not parked: the release is re-sent, so retryable. */
    @Test
    void aRefusalThatWasNotRecordedStaysRetryable() {
        Money ten = Money.aed(new BigDecimal("10.00"));
        server.expect(requestTo(BASE + "/credit/reserve")).andRespond(withSuccess(POSITION_AED, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/credit/release"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"RELEASE_EXCEEDS_RESERVATION\",\"message\":\"more than reserved\"}"));

        adapter.reserveCredit(LOAN, CUSTOMER, ten);
        generations.failRefusalWrites = true;
        assertThatThrownBy(() -> adapter.releaseUnusedReservation(LOAN, CUSTOMER, ten))
            .isInstanceOf(CustomerCreditUnavailableException.class)
            .hasMessageContaining("RELEASE_EXCEEDS_RESERVATION");
        assertThat(generations.releaseRefusedReason(LOAN)).isEmpty();
        server.verify();
    }

    /**
     * In-memory {@link ReservationGenerations} with the compare-and-set rules
     * of JdbcReservationGenerations, and switchable store failures.
     */
    static final class Generations implements ReservationGenerations {
        private final Map<LoanId, Reservation> rows = new HashMap<>();
        boolean failGenerationWrites;
        boolean failCompensationDone;
        boolean failReservationWrites;
        boolean failAnswers;
        boolean failRefusalWrites;

        @Override
        public synchronized int current(LoanId loanId) {
            Reservation row = rows.get(loanId);
            return row == null ? 0 : row.generation();
        }

        @Override
        public synchronized java.util.Optional<Reservation> find(LoanId loanId) {
            return java.util.Optional.ofNullable(rows.get(loanId));
        }

        @Override
        public synchronized void beginReservation(LoanId loanId, int generation) {
            if (failReservationWrites) {
                throw new IllegalStateException("database unavailable");
            }
            Reservation row = rows.get(loanId);
            if (row == null) {
                row = put(loanId, generation, State.RESERVING, null);
            } else if (row.generation() == generation && row.state() == null && row.pendingCompensation() == null) {
                row = put(loanId, generation, State.RESERVING, null);
            }
            if (row.generation() != generation || row.pendingCompensation() != null
                    || row.state() == null || row.state() == State.USED) {
                throw new IllegalStateException("row moved to generation " + row.generation());
            }
        }

        @Override
        public synchronized void reservationAnswered(LoanId loanId, int generation, boolean accepted) {
            if (failAnswers) {
                throw new IllegalStateException("database unavailable");
            }
            Reservation row = rows.get(loanId);
            if (row == null || row.generation() != generation) {
                return;
            }
            if (accepted && row.pendingCompensation() == null
                    && (row.state() == null || row.state() == State.RESERVING || row.state() == State.UNCONFIRMED)) {
                put(loanId, generation, State.RESERVED, null);
            } else if (!accepted && (row.state() == State.RESERVING || row.state() == State.UNCONFIRMED)) {
                put(loanId, generation, null, row.pendingCompensation());
            }
        }

        @Override
        public synchronized boolean markUsed(LoanId loanId) {
            Reservation row = rows.get(loanId);
            if (row == null) {
                return true;
            }
            if (row.state() == null || row.state() == State.USED) {
                return false;
            }
            put(loanId, row.generation(), State.USED, row.pendingCompensation());
            return true;
        }

        @Override
        public synchronized boolean beginCompensation(LoanId loanId, int generation) {
            if (failGenerationWrites) {
                throw new IllegalStateException("database unavailable");
            }
            Reservation row = rows.get(loanId);
            if (row != null && (row.generation() != generation || row.pendingCompensation() != null
                    || row.state() == null || row.state() == State.USED)) {
                return false;
            }
            put(loanId, generation + 1, null, generation);
            return true;
        }

        @Override
        public synchronized boolean beginCompensationOfUnanswered(LoanId loanId, int generation, java.time.Instant before) {
            Reservation row = rows.get(loanId);
            if (row == null || row.generation() != generation || row.pendingCompensation() != null
                    || (row.state() != State.RESERVING && row.state() != State.UNCONFIRMED)
                    || !row.updatedAt().isBefore(before)) {
                return false;
            }
            put(loanId, generation + 1, null, generation);
            return true;
        }

        @Override
        public synchronized boolean beginCompensationOfAccepted(LoanId loanId, int generation) {
            if (failGenerationWrites) {
                throw new IllegalStateException("database unavailable");
            }
            Reservation row = rows.get(loanId);
            if (row == null || row.generation() != generation || row.pendingCompensation() != null
                    || row.state() != State.RESERVED) {
                return false;
            }
            put(loanId, generation + 1, null, generation);
            return true;
        }

        @Override
        public synchronized java.util.OptionalInt pendingCompensation(LoanId loanId) {
            Reservation row = rows.get(loanId);
            return row == null || row.pendingCompensation() == null
                ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(row.pendingCompensation());
        }

        @Override
        public synchronized void compensationDone(LoanId loanId, int generation) {
            if (failCompensationDone) {
                throw new IllegalStateException("database unavailable");
            }
            Reservation row = rows.get(loanId);
            if (row != null && Integer.valueOf(generation).equals(row.pendingCompensation())) {
                put(loanId, row.generation(), row.state(), null);
            }
        }

        @Override
        public java.util.List<Unresolved> unresolved(java.time.Instant before, int limit, boolean includeUnconfirmed) {
            throw new UnsupportedOperationException("not used by the adapter");
        }

        @Override
        public boolean markUnconfirmed(LoanId loanId, int generation, java.time.Instant before) {
            throw new UnsupportedOperationException("not used by the adapter");
        }

        @Override
        public synchronized long countUnconfirmed() {
            return rows.values().stream().filter(row -> row.state() == State.UNCONFIRMED).count();
        }

        @Override
        public synchronized long countPending() {
            return rows.values().stream()
                .filter(row -> row.pendingCompensation() != null || (row.state() != null && row.state() != State.USED))
                .count();
        }

        private final Map<LoanId, String> refusals = new HashMap<>();

        @Override
        public synchronized void releaseRefused(LoanId loanId, int generation, String reason) {
            if (failRefusalWrites) {
                throw new IllegalStateException("database unavailable");
            }
            refusals.put(loanId, reason);
        }

        @Override
        public synchronized java.util.Optional<String> releaseRefusedReason(LoanId loanId) {
            return java.util.Optional.ofNullable(refusals.get(loanId));
        }

        @Override
        public synchronized long countReleaseRefused() {
            return refusals.size();
        }

        synchronized String releaseRefusal(LoanId loanId) {
            return refusals.get(loanId);
        }

        synchronized State state(LoanId loanId) {
            Reservation row = rows.get(loanId);
            return row == null ? null : row.state();
        }

        private Reservation put(LoanId loanId, int generation, State state, Integer pending) {
            Reservation row = new Reservation(loanId, generation, state, pending, java.time.Instant.EPOCH);
            rows.put(loanId, row);
            return row;
        }
    }
}
