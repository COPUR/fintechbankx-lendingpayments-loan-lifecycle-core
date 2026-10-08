package com.bank.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real HTTP adapter against a stub of svc-cus-profile-kyc (credit
 * endpoints of customer-context.yaml) and a stub token endpoint: the service
 * token is used, keys are derived from the loan, a 403 from the customer
 * service is a 503 here, a refusal is a 422, the credit is released after
 * the final repayment commits, and no database transaction is open while the
 * customer service is being asked.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=http",
    "loan.customer-credit.ledger-currency=AED",
    "loan.outbox.relay.enabled=false",
    "spring.security.oauth2.client.registration.customer-service.client-secret=stub",
    "spring.datasource.hikari.data-source-properties.ApplicationName=loan-http-it"
})
@AutoConfigureMockMvc
class CustomerCreditHttpIT {

    private static final String CUSTOMER = "CUST-12345678";
    private static final String CREDIT = "/api/v1/customers/" + CUSTOMER + "/credit";
    private static final String POSITION = """
        {"customerId":"CUST-12345678","creditLimit":100000,"usedCredit":0,"availableCredit":100000,"currency":"AED"}
        """;

    private static HttpServer stub;
    private static final Map<String, Stubbed> responses = new ConcurrentHashMap<>();
    private static final List<Call> calls = new CopyOnWriteArrayList<>();
    private static volatile JdbcTemplate probe;

    record Stubbed(int status, String body) { }

    record Call(String method, String path, String authorization, String idempotencyKey, String body,
                int idleInTransaction) { }

    @BeforeAll
    static void start() throws IOException {
        PostgresTestDatabase.assumeAvailable();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> respond(exchange, 200,
            "{\"access_token\":\"stub-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        stub.createContext("/api/v1/customers", CustomerCreditHttpIT::customer);
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
        registry.add("loan.customer-credit.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        registry.add("spring.security.oauth2.client.provider.fintechbankx.token-uri",
            () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/token");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void reset() {
        probe = jdbc;
        jdbc.update("delete from sc_ln_loan_lifecycle.repayment_allocation");
        jdbc.update("delete from sc_ln_loan_lifecycle.repayment");
        jdbc.update("delete from sc_ln_loan_lifecycle.credit_reservation_generation");
        jdbc.update("delete from sc_ln_loan_lifecycle.outbox_event");
        jdbc.update("delete from sc_ln_loan_lifecycle.loan");
        responses.clear();
        calls.clear();
        responses.put("GET " + CREDIT, new Stubbed(200, POSITION));
        responses.put("POST " + CREDIT + "/reserve", new Stubbed(200, POSITION));
        responses.put("POST " + CREDIT + "/release", new Stubbed(200, POSITION));
    }

    @Test
    void disbursementReservesWithTheServiceTokenAndTheLoanKeyAndHoldsNoTransactionOpen() throws Exception {
        String loanId = approvedLoan();

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"));

        Call reserve = only("POST", CREDIT + "/reserve");
        assertThat(reserve.authorization()).isEqualTo("Bearer stub-service-token");
        assertThat(reserve.idempotencyKey()).isEqualTo(loanId + ":reserve");
        assertThat(json.readTree(reserve.body()).get("reference").asText()).isEqualTo(loanId);
        assertThat(json.readTree(reserve.body()).get("currency").asText()).isEqualTo("AED");
        assertThat(calls).allSatisfy(call -> assertThat(call.idleInTransaction()).as(call.path()).isZero());
    }

    @Test
    void customerServiceRefusingOurTokenIsA503AndTheLoanStaysApproved() throws Exception {
        String loanId = approvedLoan();
        responses.put("POST " + CREDIT + "/reserve", new Stubbed(403, "{\"code\":\"FORBIDDEN\",\"message\":\"no\"}"));

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CUSTOMER_SERVICE_UNAVAILABLE"));

        assertThat(loanStatus(loanId)).isEqualTo("APPROVED");
    }

    @Test
    void insufficientCreditIsA422AndConcurrentUpdatesAreRetriedWithTheSameKey() throws Exception {
        String refused = approvedLoan();
        responses.put("POST " + CREDIT + "/reserve", new Stubbed(422, "{\"code\":\"INSUFFICIENT_CREDIT\",\"message\":\"no\"}"));

        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", refused)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDIT"));
        assertThat(loanStatus(refused)).isEqualTo("APPROVED");

        responses.put("POST " + CREDIT + "/reserve", new Stubbed(409, "{\"code\":\"CONCURRENT_UPDATE\",\"message\":\"retry\"}"));
        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", refused)))
            .andExpect(status().isServiceUnavailable());
        assertThat(calls.stream().filter(c -> c.path().endsWith("/reserve")).map(Call::idempotencyKey))
            .hasSize(4).containsOnly(refused + ":reserve");
    }

    @Test
    void finalRepaymentReleasesTheCreditAfterItCommitted() throws Exception {
        String loanId = approvedLoan();
        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId))).andExpect(status().isOk());
        responses.put("POST " + CREDIT + "/release", new Stubbed(200, POSITION));

        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 1032.80, \"currency\": \"AED\"}"))
            .andExpect(status().isOk());
        assertThat(calls.stream().filter(c -> c.path().endsWith("/release"))).isEmpty();

        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 11360.78, \"currency\": \"AED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULLY_PAID"));

        Call release = only("POST", CREDIT + "/release");
        assertThat(release.idempotencyKey()).isEqualTo(loanId + ":release");
        assertThat(release.idleInTransaction()).isZero();
    }

    @Test
    void failedReleaseDoesNotUndoTheFinalRepayment() throws Exception {
        String loanId = approvedLoan();
        mvc.perform(asBanker(post("/api/v1/loans/{id}/disburse", loanId))).andExpect(status().isOk());
        responses.put("POST " + CREDIT + "/release", new Stubbed(503, "{\"code\":\"UNAVAILABLE\"}"));

        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 12393.58, \"currency\": \"AED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULLY_PAID"));

        assertThat(loanStatus(loanId)).isEqualTo("FULLY_PAID");
    }

    @Test
    void creditHeldInAnotherCurrencyIsACurrencyMismatch() throws Exception {
        responses.put("GET " + CREDIT, new Stubbed(200, POSITION.replace("\"AED\"", "\"USD\"")));

        mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
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
        // Sessions of this service that sit in an open transaction while we answer
        Integer idle = probe.queryForObject("""
            select count(*) from pg_stat_activity
             where application_name = 'loan-http-it' and state like 'idle in transaction%'
            """, Integer.class);
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("x-idempotency-key"), body, idle == null ? -1 : idle));
        Stubbed stubbed = responses.getOrDefault(key, new Stubbed(404, "{\"code\":\"CUSTOMER_NOT_FOUND\"}"));
        respond(exchange, stubbed.status(), stubbed.body());
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
