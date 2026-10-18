package com.bank.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real HTTP adapter against a stub of the accounts ledger (journal
 * entries) and of the token endpoint, with the customer service stubbed as in
 * CustomerCreditHttpIT. Every answer the ledger can give a disbursement is
 * mapped here: the loan stays APPROVED and the reservation is released; an
 * answer that may have booked the entry is also signalled as an unresolved
 * booking (DevSecOps B-1, S-2). A refusal is a 422 and raises no signal.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=http",
    "loan.customer-credit.ledger-currency=AED",
    "journal.adapter=http",
    "journal.loan.currency=AED",
    "journal.loan.receivable-account-id=LOAN-RECEIVABLE",
    "journal.loan.clearing-account-id=DISBURSEMENT-CLEARING",
    "journal.loan.interest-income-account-id=INTEREST-INCOME",
    "loan.outbox.relay.enabled=false",
    "spring.security.oauth2.client.registration.customer-service.client-secret=stub",
    "spring.security.oauth2.client.registration.accounts-ledger.client-secret=stub",
    "spring.datasource.hikari.data-source-properties.ApplicationName=loan-ledger-it"
})
@AutoConfigureMockMvc
class LedgerDisbursementHttpIT {

    private static final String CUSTOMER = "CUST-12345678";
    private static final String CREDIT = "/api/v1/customers/" + CUSTOMER + "/credit";
    private static final String LEDGER_ENTRIES = "/api/v1/journal-entries";
    private static final String POSITION = """
        {"customerId":"CUST-12345678","creditLimit":100000,"usedCredit":0,"availableCredit":100000,"currency":"AED"}
        """;

    private static HttpServer stub;
    private static final Map<String, Stubbed> customerResponses = new ConcurrentHashMap<>();
    private static final List<Call> calls = new CopyOnWriteArrayList<>();
    private static volatile Stubbed ledgerAnswer;
    private static volatile long ledgerDelayMillis;

    record Stubbed(int status, String body) { }

    record Call(String method, String path, String authorization, String body) { }

    @BeforeAll
    static void start() throws IOException {
        PostgresTestDatabase.assumeAvailable();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> respond(exchange, 200,
            "{\"access_token\":\"stub-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        stub.createContext("/api/v1/customers", LedgerDisbursementHttpIT::customer);
        stub.createContext(LEDGER_ENTRIES, LedgerDisbursementHttpIT::ledger);
        stub.start();
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
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        registry.add("loan.customer-credit.base-url", () -> base);
        registry.add("journal.base-url", () -> base);
        registry.add("spring.security.oauth2.client.provider.fintechbankx.token-uri", () -> base + "/token");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void reset() {
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.repayment_allocation");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.repayment");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.credit_reservation_generation");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.outbox_event");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.repayment_booking");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.loan");
        customerResponses.clear();
        customerResponses.put("GET " + CREDIT, new Stubbed(200, POSITION));
        customerResponses.put("POST " + CREDIT + "/reserve", new Stubbed(200, POSITION));
        customerResponses.put("POST " + CREDIT + "/release", new Stubbed(200, POSITION));
        calls.clear();
        ledgerAnswer = new Stubbed(201, "{}");
        ledgerDelayMillis = 0;
    }

    @Test
    void aBookedDisbursementIsDisbursedAndTheLedgerGetsTheLoanReference() throws Exception {
        String loanId = approvedLoan();
        ledgerAnswer = new Stubbed(201, receipt(loanId));
        double before = unresolved("ledger_unanswered") + unresolved("not_stored");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"));

        Call entry = only("POST", LEDGER_ENTRIES);
        assertThat(entry.authorization()).isEqualTo("Bearer stub-service-token");
        assertThat(json.readTree(entry.body()).get("reference").asText()).isEqualTo("loan-disbursement:" + loanId);
        assertThat(loanStatus(loanId)).isEqualTo("DISBURSED");
        assertThat(unresolved("ledger_unanswered") + unresolved("not_stored") - before).isZero();
    }

    @Test
    void aTimeoutLeavesTheLoanApprovedAndSignalsThatTheEntryMayBeBooked() throws Exception {
        String loanId = approvedLoan();
        ledgerDelayMillis = 3_000;
        double before = unresolved("ledger_unanswered");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ACCOUNTS_LEDGER_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("not disbursed; the entry may have been booked; a retry replays it"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
        assertThat(releasedFor(loanId)).isEqualTo(1);
        assertThat(unresolved("ledger_unanswered") - before).isEqualTo(1.0);
    }

    @Test
    void aServerErrorIsA503LeavesTheLoanApprovedAndSignalsTheBooking() throws Exception {
        String loanId = approvedLoan();
        ledgerAnswer = new Stubbed(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}");
        double before = unresolved("ledger_unanswered");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ACCOUNTS_LEDGER_UNAVAILABLE"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
        assertThat(releasedFor(loanId)).isEqualTo(1);
        assertThat(unresolved("ledger_unanswered") - before).isEqualTo(1.0);
    }

    @Test
    void anUnauthorizedLedgerFailsClosedAsA503AndTheReservationIsReleased() throws Exception {
        String loanId = approvedLoan();
        ledgerAnswer = new Stubbed(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"token rejected\"}");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ACCOUNTS_LEDGER_UNAVAILABLE"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
        assertThat(releasedFor(loanId)).isEqualTo(1);
    }

    @Test
    void aRefusedEntryIsA422WithTheReservationReleasedAndNoSignal() throws Exception {
        String loanId = approvedLoan();
        ledgerAnswer = new Stubbed(422, "{\"code\":\"ACCOUNT_NOT_OPERATIONAL\",\"message\":\"account LOAN-RECEIVABLE is frozen\"}");
        double before = unresolved("ledger_unanswered") + unresolved("not_stored");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_REJECTED"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
        assertThat(releasedFor(loanId)).isEqualTo(1);
        assertThat(unresolved("ledger_unanswered") + unresolved("not_stored") - before).isZero();
    }

    @Test
    void anUnreadableAnswerIsA503AndSignalsTheBooking() throws Exception {
        String loanId = approvedLoan();
        ledgerAnswer = new Stubbed(201, "{not json");
        double before = unresolved("ledger_unanswered");

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ACCOUNTS_LEDGER_UNAVAILABLE"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
        assertThat(releasedFor(loanId)).isEqualTo(1);
        assertThat(unresolved("ledger_unanswered") - before).isEqualTo(1.0);
    }

    private double unresolved(String cause) {
        var counter = meters.find("loan_disbursement_booking_unresolved_total").tag("cause", cause).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long releasedFor(String loanId) {
        return calls.stream().filter(c -> c.method().equals("POST") && c.path().equals(CREDIT + "/release"))
            .filter(c -> c.body().contains(loanId)).count();
    }

    private String receipt(String loanId) {
        return """
            {"journalEntryId":"JE-%s","reference":"loan-disbursement:%s","bookedAt":"2026-10-18T06:00:00Z","replay":false}
            """.formatted(loanId, loanId);
    }

    private String approvedLoan() throws Exception {
        String body = mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String loanId = json.readTree(body).get("loanId").asText();
        mvc.perform(asBanker(post("/api/v1/loans/{id}/approve", loanId))).andExpect(status().isOk());
        return loanId;
    }

    private static String loanBody() {
        return """
            {"customerId": "%s", "principalAmount": 12000.00, "currency": "AED",
             "annualInterestRate": 6.0, "termInMonths": 12}
            """.formatted(CUSTOMER);
    }

    private String loanStatus(String loanId) {
        return jdbc.queryForObject("select status from sc_ln_loan_lifecycle.loan where loan_id = ?", String.class, loanId);
    }

    private static Call only(String method, String path) {
        List<Call> matching = calls.stream().filter(c -> c.method().equals(method) && c.path().equals(path)).toList();
        assertThat(matching).as(method + " " + path).hasSize(1);
        return matching.getFirst();
    }

    private static void customer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"), body));
        Stubbed stubbed = customerResponses.getOrDefault(key, new Stubbed(404, "{\"code\":\"CUSTOMER_NOT_FOUND\"}"));
        respond(exchange, stubbed.status(), stubbed.body());
    }

    private static void ledger(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"), body));
        if (ledgerDelayMillis > 0) {
            try {
                Thread.sleep(ledgerDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, ledgerAnswer.status(), ledgerAnswer.body());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return request.with(LoanLifecycleServiceIT.token("5f0c2b7e-8d1a-4c3e-9b6f-2a7d4e1c9b30", CUSTOMER, "ROLE_CUSTOMER"));
    }

    private static MockHttpServletRequestBuilder asBanker(MockHttpServletRequestBuilder request) {
        return request.with(LoanLifecycleServiceIT.token("8a1d3c55-0b6e-4f7a-a2c9-1e4b7d9f6a20", null, "ROLE_BANKER"));
    }
}
