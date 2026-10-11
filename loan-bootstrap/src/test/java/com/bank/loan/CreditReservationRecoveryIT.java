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
 * whose accepted reservation was never released, reserves that were sent but
 * never answered (RESERVING), a row an earlier release marked UNCONFIRMED,
 * and a disbursed loan.
 *
 * <p>Customer CRC decision 2026-10-10 (customer PR #13 commit a6ebe01): a
 * release whose reference matches no reservation is always 422
 * RESERVATION_NOT_FOUND, so with CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED
 * on (the environment has #13 deployed) the sweep releases every one of them
 * on start-up under its compensation key with the loan id as reference:
 * accepted means released; RESERVATION_NOT_FOUND means the reserve was never
 * applied, or holds nothing any more, and the row is cleared
 * (loan_credit_releases_unmatched_total); RELEASE_EXCEEDS_RESERVATION leaves
 * the row for an operator
 * (loan_credit_reservations_operator{reason="release_exceeds_reservation"}).
 * Nothing is left UNCONFIRMED, the reason="unconfirmed" gauge is not
 * exported, and the disbursed loan is left alone. The default (the flag off)
 * is covered by CreditReservationSweepTest and ReservationGenerationsPostgresIT.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=http",
    "loan.customer-credit.ledger-currency=AED",
    "loan.outbox.relay.enabled=false",
    "loan.credit-reservation.sweep.grace=PT10S",
    "loan.credit-reservation.sweep.interval=PT1S",
    "loan.credit-reservation.sweep.release-unanswered=true",
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
        loan(owner, "LOAN-RECOVER-APPLIED", "CANCELLED");
        loan(owner, "LOAN-RECOVER-LEGACY", "REJECTED");
        loan(owner, "LOAN-RECOVER-EXCEEDS", "CANCELLED");
        loan(owner, "LOAN-RECOVER-DISBURSED", "DISBURSED");
        row(owner, "LOAN-RECOVER-PENDING", 1, null, 0);
        row(owner, "LOAN-RECOVER-HELD", 0, "RESERVED", null);
        row(owner, "LOAN-RECOVER-UNKNOWN", 0, "RESERVING", null);
        row(owner, "LOAN-RECOVER-APPLIED", 0, "RESERVING", null);
        row(owner, "LOAN-RECOVER-LEGACY", 0, "UNCONFIRMED", null);
        row(owner, "LOAN-RECOVER-EXCEEDS", 1, "RESERVING", null);
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
    void onStartUpTheSweepReleasesEveryUnusedReservationByItsReference() throws InterruptedException {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline) && !(releases.size() >= 6 && refusedCode(owner, "LOAN-RECOVER-EXCEEDS") != null
                && pending(owner, "LOAN-RECOVER-UNKNOWN") == null)) {
            Thread.sleep(100);
        }

        assertThat(releases).containsExactlyInAnyOrder(
            "LOAN-RECOVER-PENDING:reserve:compensation 12000.00 LOAN-RECOVER-PENDING",
            "LOAN-RECOVER-HELD:reserve:compensation 12000.00 LOAN-RECOVER-HELD",
            "LOAN-RECOVER-UNKNOWN:reserve:compensation 12000.00 LOAN-RECOVER-UNKNOWN",
            "LOAN-RECOVER-APPLIED:reserve:compensation 12000.00 LOAN-RECOVER-APPLIED",
            "LOAN-RECOVER-LEGACY:reserve:compensation 12000.00 LOAN-RECOVER-LEGACY",
            "LOAN-RECOVER-EXCEEDS:reserve:g1:compensation 12000.00 LOAN-RECOVER-EXCEEDS");
        for (String released : List.of("LOAN-RECOVER-HELD", "LOAN-RECOVER-UNKNOWN", "LOAN-RECOVER-APPLIED", "LOAN-RECOVER-LEGACY")) {
            assertThat(owner.queryForMap("select generation, pending_compensation, reservation_state, release_refused_code from "
                    + SCHEMA + ".credit_reservation_generation where loan_id = ?", released))
                .as(released)
                .containsEntry("generation", 1).containsEntry("pending_compensation", null)
                .containsEntry("reservation_state", null).containsEntry("release_refused_code", null);
        }
        assertThat(pending(owner, "LOAN-RECOVER-PENDING")).isNull();
        assertThat(owner.queryForMap("select generation, pending_compensation, reservation_state, release_refused_code from "
                + SCHEMA + ".credit_reservation_generation where loan_id = 'LOAN-RECOVER-EXCEEDS'"))
            .containsEntry("generation", 2).containsEntry("pending_compensation", 1)
            .containsEntry("reservation_state", null).containsEntry("release_refused_code", "RELEASE_EXCEEDS_RESERVATION");
        assertThat(state(owner, "LOAN-RECOVER-DISBURSED")).isEqualTo("RESERVED");
        assertThat(owner.queryForObject("select count(*) from " + SCHEMA
            + ".credit_reservation_generation where reservation_state = 'UNCONFIRMED'", Integer.class)).isZero();

        // nothing is left UNCONFIRMED for an operator any more: that reason is gone
        assertThat(meters.find("loan.credit.reservations.operator").tag("reason", "unconfirmed").gauge()).isNull();
        assertThat(meters.get("loan.credit.reservations.operator").tag("reason", "release_exceeds_reservation")
            .gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("loan.credit.releases.unmatched").counter().count()).isEqualTo(1.0);
        // the refused release may still hold credit
        assertThat(meters.get("loan.credit.reservations.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("loan.credit.reservations.pending").gauge().getId().getTags())
            .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("LOAN-", "CUST-"));
    }

    private static Integer pending(JdbcTemplate owner, String loanId) {
        return owner.queryForObject("select pending_compensation from " + SCHEMA
            + ".credit_reservation_generation where loan_id = ?", Integer.class, loanId);
    }

    private static String refusedCode(JdbcTemplate owner, String loanId) {
        return owner.queryForObject("select release_refused_code from " + SCHEMA
            + ".credit_reservation_generation where loan_id = ?", String.class, loanId);
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
            // customer PR #13 a6ebe01: an unknown reference is 422 RESERVATION_NOT_FOUND, never untracked credit
            if ("LOAN-RECOVER-UNKNOWN".equals(json.get("reference"))) {
                respond(exchange, 422, "{\"code\":\"RESERVATION_NOT_FOUND\",\"message\":\"no reservation\"}");
                return;
            }
            if ("LOAN-RECOVER-EXCEEDS".equals(json.get("reference"))) {
                respond(exchange, 422, "{\"code\":\"RELEASE_EXCEEDS_RESERVATION\",\"message\":\"more than held\"}");
                return;
            }
        }
        respond(exchange, POSITION);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
