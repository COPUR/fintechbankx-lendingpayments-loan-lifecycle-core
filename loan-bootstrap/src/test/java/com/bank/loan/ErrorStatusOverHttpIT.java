package com.bank.loan;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.anyString;
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void customerToken() {
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
