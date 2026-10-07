package com.bank.loan;

import com.bank.loan.application.LoanManagementService;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanRepository;
import com.bank.loan.infrastructure.outbox.OutboxRelay;
import com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds
 * sc_ln_loan_lifecycle, Hibernate validates the entities against it, and a
 * loan goes through its lifecycle over HTTP with its events landing in the
 * outbox and then on (a mocked) Kafka.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=in-memory",
    "loan.outbox.relay.enabled=false"
})
@AutoConfigureMockMvc
class LoanLifecycleServiceIT {

    private static final String CUSTOMER = "CUST-12345678";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanRepository loans;
    @Autowired LoanManagementService loanService;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from sc_ln_loan_lifecycle.outbox_event");
        jdbc.update("delete from sc_ln_loan_lifecycle.loan");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_ln_loan_lifecycle' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("loan", "loan_installment", "outbox_event");
    }

    @Test
    void loanLifecycleOverHttpPersistsStateAndWritesEveryEventToTheOutbox() throws Exception {
        String loanId = createLoan("10000.00");

        mvc.perform(asOfficer(post("/api/v1/loans/{id}/approve", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(asOfficer(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"));
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10000.00, \"currency\": \"AED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULLY_PAID"));

        assertThat(jdbc.queryForMap("select status, outstanding_balance, version from sc_ln_loan_lifecycle.loan where loan_id = ?", loanId))
            .containsEntry("status", "FULLY_PAID")
            .containsEntry("version", 3L)
            .hasEntrySatisfying("outstanding_balance", v -> assertThat((BigDecimal) v).isEqualByComparingTo("0"));

        List<String> eventTypes = jdbc.queryForList(
            "select event_type from sc_ln_loan_lifecycle.outbox_event where aggregate_id = ? order by created_seq",
            String.class, loanId);
        assertThat(eventTypes).containsExactly(
            "Lending.Loan.Created.v1",
            "Lending.Loan.Approved.v1",
            "Lending.Loan.Disbursed.v1",
            "Lending.Loan.PaymentMade.v1",
            "Lending.Loan.FullyPaid.v1");

        JsonNode created = json.readTree(jdbc.queryForObject(
            "select payload::text from sc_ln_loan_lifecycle.outbox_event where aggregate_id = ? and event_type = 'Lending.Loan.Created.v1'",
            String.class, loanId));
        assertThat(created.get("producer").asText()).isEqualTo("svc-ln-loan-lifecycle");
        assertThat(created.get("correlationId").asText()).isEqualTo("it-interaction-1");
        assertThat(created.get("aggregateVersion").asLong()).isZero();
        assertThat(created.at("/data/principalAmount/amount").asText()).isEqualTo("10000.00");
        assertThat(created.at("/data/principalAmount/currency").asText()).isEqualTo("AED");
    }

    @Test
    void loanWithScheduleRoundTripsThroughTheRepository() {
        Loan loan = Loan.createWithInstallments(LoanId.of("LOAN-SCHED-1"),
            com.bank.shared.kernel.domain.CustomerId.of(CUSTOMER),
            com.bank.shared.kernel.domain.Money.aed(new BigDecimal("12000.00")),
            com.bank.loan.domain.InterestRate.of(new BigDecimal("6.0")),
            com.bank.loan.domain.LoanTerm.ofMonths(12));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> loans.save(loan));

        Loan reloaded = loans.findById(LoanId.of("LOAN-SCHED-1")).orElseThrow();

        assertThat(reloaded.getInstallments()).hasSize(12);
        assertThat(reloaded.getTotalInstallmentAmount()).isEqualTo(loan.getTotalInstallmentAmount());
        assertThat(reloaded.getInstallments().getFirst().getDueDate()).isEqualTo(loan.getApplicationDate().plusMonths(1));
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getDomainEvents()).isEmpty();
    }

    @Test
    void staleAggregateCannotOverwriteANewerVersion() throws Exception {
        String loanId = createLoan("5000.00");
        Loan stale = loans.findById(LoanId.of(loanId)).orElseThrow();
        loanService.approveLoan(loanId);

        stale.cancel("customer withdrew");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> loans.save(stale)))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("select status from sc_ln_loan_lifecycle.loan where loan_id = ?", String.class, loanId))
            .isEqualTo("APPROVED");
    }

    @Test
    void relayPublishesPendingEventsInOrderKeyedByLoanId() throws Exception {
        String loanId = createLoan("7000.00");
        loanService.approveLoan(loanId);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7));

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(2);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> records = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::topic)
            .containsExactly("evt.ln.loan.created.v1", "evt.ln.loan.approved.v1");
        assertThat(records.getAllValues()).extracting(ProducerRecord::key).containsOnly(loanId);
    }

    @Test
    void unknownLoanIsA404WithTheInteractionId() throws Exception {
        mvc.perform(asCustomer(get("/api/v1/loans/{id}", "LOAN-MISSING")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LOAN_NOT_FOUND"))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));
    }

    @Test
    void apiRejectsCallsWithoutAToken() throws Exception {
        mvc.perform(get("/api/v1/loans/{id}", "LOAN-ANY")).andExpect(status().isUnauthorized());
    }

    private String createLoan(String amount) throws Exception {
        String body = mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "%s", "principalAmount": %s, "currency": "AED",
                     "annualInterestRate": 6.5, "termInMonths": 24}
                    """.formatted(CUSTOMER, amount)))
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("loanId").asText();
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-1")
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private static MockHttpServletRequestBuilder asOfficer(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-2")
            .with(jwt().jwt(j -> j.subject("officer-1")).authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BANKER")));
    }
}
