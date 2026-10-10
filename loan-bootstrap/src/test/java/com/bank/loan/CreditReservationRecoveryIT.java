package com.bank.loan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review 5460235552: a restart re-drives what the previous process left
 * behind. Before the service starts, the migration Job has run and the
 * database holds the rows a crash would leave: a cancelled loan whose
 * compensating release was recorded but never confirmed, a cancelled loan
 * whose accepted reservation was never released, an approved loan whose
 * reserve was sent but never answered, and a disbursed loan. On start-up the
 * recovery sweep re-sends the two releases under their compensation keys,
 * releases nothing for the unanswered reserve (it marks it UNCONFIRMED for an
 * operator) and leaves the disbursed loan alone.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=http",
    "loan.customer-credit.ledger-currency=AED",
    "loan.outbox.relay.enabled=false",
    "loan.credit-reservation.sweep.grace=PT0S",
    "loan.credit-reservation.sweep.interval=PT1S",
    "spring.security.oauth2.client.registration.customer-service.client-secret=stub",
    "spring.datasource.hikari.data-source-properties.ApplicationName=loan-recovery-it"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreditReservationRecoveryIT {

    private static final String SCHEMA = "sc_ln_loan_lifecycle";
    private static final String CUSTOMER = "CUST-RECOVER-1";
    private static final String POSITION = """
        {"customerId":"CUST-RECOVER-1","creditLimit":100000,"usedCredit":0,"availableCredit":100000,"currency":"AED"}
        """;

    private static HttpServer stub;
    private static final List<String> releases = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void previousProcessLeftRowsBehind() throws IOException {
        PostgresTestDatabase.assumeAvailable();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> respond(exchange,
            "{\"access_token\":\"stub-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        stub.createContext("/api/v1/customers", CreditReservationRecoveryIT::customer);
        stub.start();

        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(LoanLifecycleApplication.run("migrate",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword())).isZero();
        for (String table : List.of("repayment_allocation", "repayment", "inbox_message",
                "credit_reservation_generation", "outbox_event", "loan_installment", "loan")) {
            owner.update("delete from " + SCHEMA + "." + table);
        }
        loan(owner, "LOAN-RECOVER-PENDING", "CANCELLED");
        loan(owner, "LOAN-RECOVER-HELD", "CANCELLED");
        loan(owner, "LOAN-RECOVER-UNKNOWN", "APPROVED");
        loan(owner, "LOAN-RECOVER-DISBURSED", "DISBURSED");
        row(owner, "LOAN-RECOVER-PENDING", 1, null, 0);
        row(owner, "LOAN-RECOVER-HELD", 0, "RESERVED", null);
        row(owner, "LOAN-RECOVER-UNKNOWN", 0, "RESERVING", null);
        row(owner, "LOAN-RECOVER-DISBURSED", 0, "RESERVED", null);
    }

    @AfterAll
    static void stop() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("loan.customer-credit.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        registry.add("spring.security.oauth2.client.provider.fintechbankx.token-uri",
            () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/token");
    }

    @Autowired MeterRegistry meters;
    @MockBean KafkaTemplate<String, String> kafka;

    @Test
    void onStartUpTheSweepResendsRecordedReleasesAndReleasesNothingBlind() throws InterruptedException {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline) && !(releases.size() >= 2 && "UNCONFIRMED".equals(state(owner, "LOAN-RECOVER-UNKNOWN")))) {
            Thread.sleep(100);
        }

        assertThat(releases).containsExactlyInAnyOrder(
            "LOAN-RECOVER-PENDING:reserve:compensation 12000.00 LOAN-RECOVER-PENDING",
            "LOAN-RECOVER-HELD:reserve:compensation 12000.00 LOAN-RECOVER-HELD");
        assertThat(owner.queryForMap("select generation, pending_compensation, reservation_state from " + SCHEMA
                + ".credit_reservation_generation where loan_id = 'LOAN-RECOVER-HELD'"))
            .containsEntry("generation", 1).containsEntry("pending_compensation", null).containsEntry("reservation_state", null);
        assertThat(owner.queryForObject("select pending_compensation from " + SCHEMA
            + ".credit_reservation_generation where loan_id = 'LOAN-RECOVER-PENDING'", Integer.class)).isNull();
        assertThat(state(owner, "LOAN-RECOVER-UNKNOWN")).isEqualTo("UNCONFIRMED");
        assertThat(state(owner, "LOAN-RECOVER-DISBURSED")).isEqualTo("RESERVED");

        assertThat(meters.get("loan.credit.reservations.operator").tag("reason", "unconfirmed").gauge().value()).isEqualTo(1.0);
        // the unconfirmed reservation may still hold credit
        assertThat(meters.get("loan.credit.reservations.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("loan.credit.reservations.pending").gauge().getId().getTags())
            .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("LOAN-", "CUST-"));
    }

    private static String state(JdbcTemplate owner, String loanId) {
        return owner.queryForObject("select reservation_state from " + SCHEMA
            + ".credit_reservation_generation where loan_id = ?", String.class, loanId);
    }

    private static void loan(JdbcTemplate owner, String loanId, String status) {
        owner.update("insert into " + SCHEMA + ".loan (loan_id, customer_id, principal_amount, currency, annual_interest_rate,"
            + " term_months, status, application_date, outstanding_balance, created_at, updated_at)"
            + " values (?, ?, 12000.0000, 'AED', 6.0, 12, ?, current_date, 12000.0000, now(), now())",
            loanId, CUSTOMER, status);
    }

    private static void row(JdbcTemplate owner, String loanId, int generation, String state, Integer pending) {
        owner.update("insert into " + SCHEMA + ".credit_reservation_generation"
            + " (loan_id, generation, reservation_state, pending_compensation, updated_at)"
            + " values (?, ?, ?, ?, now() - interval '1 hour')", loanId, generation, state, pending);
    }

    private static void customer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (exchange.getRequestURI().getPath().endsWith("/credit/release")) {
            Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            releases.add(exchange.getRequestHeaders().getFirst("x-idempotency-key") + " "
                + new java.math.BigDecimal(json.get("amount").toString()).setScale(2) + " " + json.get("reference"));
        }
        respond(exchange, POSITION);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
