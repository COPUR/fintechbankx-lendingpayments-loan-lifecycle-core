package com.bank.loan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.CreditReservationNeedsOperatorException;
import com.bank.loan.domain.port.out.CustomerCreditService;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Over a real HTTP connection (MockMvc does not forward to /error): errors
 * Spring resolves through the error page keep their status instead of
 * turning into 403, and malformed bodies are 400 (regression run
 * 2026-10-08, item 2). The real JWT converter runs here, so the customer
 * is identified by the customer_id claim while the subject is a UUID.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "loan.customer-credit.adapter=in-memory",
    "loan.customer-credit.ledger-currency=AED",
    "loan.outbox.relay.enabled=false"
})
class ErrorStatusOverHttpIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @LocalServerPort int port;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean KafkaTemplate<String, String> kafka;
    @SpyBean CustomerCreditService customerCredit;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void customerToken() {
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.credit_reservation_generation");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.outbox_event");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.loan");
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("t").header("alg", "RS256")
            .subject("5f0c2b7e-8d1a-4c3e-9b6f-2a7d4e1c9b30").claim("customer_id", "CUST-12345678")
            .claim("realm_access", Map.of("roles", List.of("customer")))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    @Test
    void malformedJsonIsA400() throws Exception {
        HttpResponse<String> response = post("/api/v1/loans", "application/json", "{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST");
    }

    @Test
    void unsupportedMediaTypeKeepsItsStatusInsteadOf403() throws Exception {
        HttpResponse<String> response = post("/api/v1/loans", "text/plain", "hello");

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void wrongMethodKeepsItsStatusInsteadOf403() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/loans"))
            .header("Authorization", "Bearer t").DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void customerIdClaimNotTheUuidSubjectIdentifiesTheCustomer() throws Exception {
        HttpResponse<String> own = post("/api/v1/loans", "application/json", """
            {"customerId": "CUST-12345678", "principalAmount": 5000.00, "currency": "AED",
             "annualInterestRate": 6.0, "termInMonths": 12}
            """);
        HttpResponse<String> other = post("/api/v1/loans", "application/json", """
            {"customerId": "CUST-87654321", "principalAmount": 5000.00, "currency": "AED",
             "annualInterestRate": 6.0, "termInMonths": 12}
            """);

        assertThat(own.statusCode()).isEqualTo(201);
        assertThat(other.statusCode()).isEqualTo(403);
    }

    @Test
    void monolithOnlyEndpointsAre404Or405NotForbidden() throws Exception {
        assertThat(get("/api/v1/loans").statusCode()).isEqualTo(405);
        assertThat(get("/api/v1/loans/LOAN-X/events").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/loans/LOAN-X/amortization-schedule").statusCode()).isEqualTo(404);
    }

    /**
     * Review minor (CustomerProfileHttpAdapter sendCompensation): a loan whose
     * credit reservation is held for an operator answers 409
     * CREDIT_RESERVATION_HELD_FOR_OPERATOR, not the retryable 503.
     */
    @Test
    void aDisbursementOfALoanHeldForAnOperatorIsA409NotA503() throws Exception {
        PostgresTestDatabase.owner().update("insert into sc_ln_loan_lifecycle.loan (loan_id, customer_id, principal_amount,"
            + " currency, annual_interest_rate, term_months, status, application_date, outstanding_balance, created_at, updated_at)"
            + " values ('LOAN-HELD-1', 'CUST-12345678', 12000.0000, 'AED', 6.0, 12, 'APPROVED', current_date, 12000.0000, now(), now())");
        doThrow(new CreditReservationNeedsOperatorException("RELEASE_EXCEEDS_RESERVATION",
                "Release LOAN-HELD-1:reserve:compensation was refused with RELEASE_EXCEEDS_RESERVATION"))
            .when(customerCredit).reserveCredit(eq(LoanId.of("LOAN-HELD-1")), any(), any());
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("t").header("alg", "RS256")
            .subject("0b6f2c1e-4f7a-4d0e-8c55-6a3e2d9b1f70")
            .claim("realm_access", Map.of("roles", List.of("banker")))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());

        HttpResponse<String> response = post("/api/v1/loans/LOAN-HELD-1/disburse", "application/json", "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"CREDIT_RESERVATION_HELD_FOR_OPERATOR\"")
            .doesNotContain("retry").doesNotContain("LOAN-HELD-1:reserve");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer t").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutATokenItIsStill401() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/loans/X"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer t")
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
