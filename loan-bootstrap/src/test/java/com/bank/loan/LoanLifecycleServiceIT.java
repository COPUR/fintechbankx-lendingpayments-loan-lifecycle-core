package com.bank.loan;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.in.LoanDecisionUseCase;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.out.LoanRepository;
import com.bank.loan.infrastructure.messaging.JdbcInbox;
import com.bank.loan.infrastructure.messaging.LoanPaymentCompletedListener;
import com.bank.loan.infrastructure.outbox.OutboxRelay;
import com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
 * loan goes through its lifecycle over HTTP with its repayments allocated,
 * its history recorded and its events landing in the outbox and then on (a
 * mocked) Kafka. Credit comes from the in-memory adapter here; the HTTP
 * adapter is covered by CustomerCreditHttpIT.
 */
@SpringBootTest(properties = {
    "loan.customer-credit.adapter=in-memory",
    "loan.customer-credit.ledger-currency=AED",
    "loan.outbox.relay.enabled=false"
})
@AutoConfigureMockMvc
class LoanLifecycleServiceIT {

    /** Customers the monolith's credit stub knows (and the regression suite seeds). */
    private static final String CUSTOMER = "CUST-12345678";
    private static final String OTHER_CUSTOMER = "CUST-87654321";

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
    @Autowired LoanDecisionUseCase decisions;
    @Autowired LoanRepaymentUseCase repayments;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.inbox_message");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.repayment_allocation");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.repayment");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.credit_reservation_generation");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.outbox_event");
        PostgresTestDatabase.owner().update("delete from sc_ln_loan_lifecycle.loan");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_ln_loan_lifecycle' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("credit_reservation_generation", "inbox_message", "loan", "loan_installment",
            "outbox_event", "repayment", "repayment_allocation");
    }

    /**
     * The service connects as a runtime role with DML only: it cannot run DDL
     * (it does not own the tables or the schema) and cannot rewrite the
     * repayment ledger, which the code only inserts into.
     */
    @Test
    void theRuntimeRoleCannotRunDdlOrRewriteTheRepaymentLedger() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.execute("create table sc_ln_loan_lifecycle.shadow (id int)"))
            .rootCause().hasMessageContaining("permission denied for schema sc_ln_loan_lifecycle");
        assertThatThrownBy(() -> runtime.execute("alter table sc_ln_loan_lifecycle.loan add column shadow int"))
            .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("loan");
        assertThatThrownBy(() -> runtime.execute("drop table sc_ln_loan_lifecycle.outbox_event"))
            .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("outbox_event");
        assertThatThrownBy(() -> runtime.execute("truncate sc_ln_loan_lifecycle.loan"))
            .rootCause().hasMessageContaining("permission denied for table loan");
        assertThatThrownBy(() -> runtime.update("update sc_ln_loan_lifecycle.repayment set amount = 0"))
            .rootCause().hasMessageContaining("permission denied for table repayment");
        assertThatThrownBy(() -> runtime.update("delete from sc_ln_loan_lifecycle.repayment_allocation"))
            .rootCause().hasMessageContaining("permission denied for table repayment_allocation");
        assertThatThrownBy(() -> runtime.update("delete from sc_ln_loan_lifecycle.loan"))
            .rootCause().hasMessageContaining("permission denied for table loan");
        assertThatThrownBy(() -> runtime.queryForObject(
                "select count(*) from sc_ln_loan_lifecycle.flyway_schema_history", Integer.class))
            .rootCause().hasMessageContaining("permission denied for table flyway_schema_history");

        assertThat(runtime.queryForObject("select count(*) from sc_ln_loan_lifecycle.loan", Integer.class)).isZero();
    }

    @Test
    void loanLifecycleOverHttpAllocatesRepaymentsAndWritesEveryEventToTheOutbox() throws Exception {
        String loanId = createLoan("12000.00", 12, "6.0");

        mvc.perform(asOfficer(post("/api/v1/loans/{id}/approve", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(asOfficer(post("/api/v1/loans/{id}/disburse", loanId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISBURSED"))
            .andExpect(jsonPath("$.outstandingBalance").value(12393.58))
            .andExpect(jsonPath("$.monthlyPayment").value(1032.80))
            .andExpect(jsonPath("$.rateBasis").value("NOMINAL_ANNUAL"));
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 1100.00, \"currency\": \"AED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outstandingBalance").value(11293.58));
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 11293.58, \"currency\": \"AED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULLY_PAID"))
            .andExpect(jsonPath("$.outstandingBalance").value(0));

        assertThat(jdbc.queryForMap("select status, outstanding_balance, version from sc_ln_loan_lifecycle.loan where loan_id = ?", loanId))
            .containsEntry("status", "FULLY_PAID")
            .containsEntry("version", 4L)
            .hasEntrySatisfying("outstanding_balance", v -> assertThat((BigDecimal) v).isEqualByComparingTo("0"));

        List<java.util.Map<String, Object>> firstAllocations = jdbc.queryForList("""
            select a.installment_number, a.amount, a.principal, a.interest
              from sc_ln_loan_lifecycle.repayment_allocation a
              join sc_ln_loan_lifecycle.repayment r on r.payment_id = a.payment_id
             where r.loan_id = ? and r.amount = 1100.00
             order by a.installment_number
            """, loanId);
        assertThat(firstAllocations).hasSize(2);
        assertThat((BigDecimal) firstAllocations.get(1).get("interest")).isEqualByComparingTo("55.14");
        assertThat((BigDecimal) firstAllocations.get(1).get("principal")).isEqualByComparingTo("12.06");
        assertThat(jdbc.queryForObject("""
            select sum(amount) from sc_ln_loan_lifecycle.repayment_allocation where loan_id = ?
            """, BigDecimal.class, loanId)).isEqualByComparingTo("12393.58");
        assertThat(jdbc.queryForList("select source from sc_ln_loan_lifecycle.repayment where loan_id = ?", String.class, loanId))
            .containsOnly("API");

        List<String> eventTypes = jdbc.queryForList(
            "select event_type from sc_ln_loan_lifecycle.outbox_event where aggregate_id = ? order by created_seq",
            String.class, loanId);
        assertThat(eventTypes).containsExactly(
            "Lending.Loan.Created.v1",
            "Lending.Loan.Approved.v1",
            "Lending.Loan.Disbursed.v1",
            "Lending.Loan.PaymentMade.v1",
            "Lending.Loan.PaymentMade.v1",
            "Lending.Loan.FullyPaid.v1");

        JsonNode created = json.readTree(jdbc.queryForObject(
            "select payload::text from sc_ln_loan_lifecycle.outbox_event where aggregate_id = ? and event_type = 'Lending.Loan.Created.v1'",
            String.class, loanId));
        assertThat(created.get("producer").asText()).isEqualTo("svc-ln-loan-lifecycle");
        assertThat(created.get("correlationId").asText()).isEqualTo("it-interaction-1");
        assertThat(created.get("aggregateVersion").asLong()).isZero();
        assertThat(created.at("/data/principalAmount/amount").asText()).isEqualTo("12000.00");
        assertThat(created.at("/data/principalAmount/currency").asText()).isEqualTo("AED");
    }

    // --- ownership -------------------------------------------------------------

    @Test
    void anotherCustomerCannotReadPayOrCancelTheLoan() throws Exception {
        String loanId = createLoan("5000.00", 12, "6.0");

        mvc.perform(as(OTHER_CUSTOMER, "ROLE_CUSTOMER", get("/api/v1/loans/{id}", loanId)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(OTHER_CUSTOMER, "ROLE_CUSTOMER", post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10.00, \"currency\": \"AED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(as(OTHER_CUSTOMER, "ROLE_CUSTOMER", post("/api/v1/loans/{id}/cancel", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"not mine\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(asCustomer(get("/api/v1/loans/{id}", loanId))).andExpect(status().isOk());
        mvc.perform(as("svc-pay-initiation-settlement", "ROLE_SERVICE", get("/api/v1/loans/{id}", loanId)))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from sc_ln_loan_lifecycle.loan where loan_id = ?", String.class, loanId))
            .isEqualTo("CREATED");
    }

    @Test
    void keycloakCustomerWithAUuidSubjectReadsAndCancelsOwnLoanButNotAnothers() throws Exception {
        String own = createLoan("5000.00", 12, "6.0");
        String sub = "5f0c2b7e-8d1a-4c3e-9b6f-2a7d4e1c9b30";

        mvc.perform(get("/api/v1/loans/{id}", own).with(token(sub, CUSTOMER, "ROLE_CUSTOMER")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/loans/{id}", own).with(token(sub, OTHER_CUSTOMER, "ROLE_CUSTOMER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/loans/{id}/cancel", own).with(token(sub, OTHER_CUSTOMER, "ROLE_CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"not mine\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/loans/{id}/cancel", own).with(token(sub, CUSTOMER, "ROLE_CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"changed my mind\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void customerTokenWithoutTheCustomerIdClaimIsRefusedEvenIfItsSubjectMatches() throws Exception {
        String loanId = createLoan("5000.00", 12, "6.0");

        mvc.perform(get("/api/v1/loans/{id}", loanId).with(token(CUSTOMER, null, "ROLE_CUSTOMER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotApplyForAnotherCustomer() throws Exception {
        mvc.perform(as(OTHER_CUSTOMER, "ROLE_CUSTOMER", post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody(CUSTOMER, "5000.00", 12, "6.0")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.loan", Integer.class)).isZero();
    }

    @Test
    void customerCannotApproveRejectOrDisburse() throws Exception {
        String loanId = createLoan("5000.00", 12, "6.0");

        mvc.perform(asCustomer(post("/api/v1/loans/{id}/approve", loanId))).andExpect(status().isForbidden());
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/reject", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/disburse", loanId))).andExpect(status().isForbidden());
    }

    // --- validation and credit ------------------------------------------------

    @Test
    void loanInAnotherCurrencyThanTheCreditIsACurrencyMismatchNotInsufficientCredit() throws Exception {
        mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody(CUSTOMER, "5000.00", 12, "6.0").replace("\"AED\"", "\"USD\"")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void loanWithoutCurrencyIsA400() throws Exception {
        mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": \"%s\", \"principalAmount\": 5000.00, \"annualInterestRate\": 6.0, \"termInMonths\": 12}"
                    .formatted(CUSTOMER)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidPaymentRequestsAre400() throws Exception {
        String loanId = disbursedLoan("5000.00");

        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"currency\": \"AED\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": -1, \"currency\": \"AED\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10, \"currency\": \"aed\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .header("x-idempotency-key", "k".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10, \"currency\": \"AED\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.repayment", Integer.class)).isZero();
    }

    @Test
    void unknownCustomerIsNotReportedAsInsufficientCredit() throws Exception {
        mvc.perform(as("CUST-UNKNOWN", "ROLE_CUSTOMER", post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody("CUST-UNKNOWN", "5000.00", 12, "6.0")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    // --- idempotency and the payment event ---------------------------------------

    @Test
    void repeatedPaymentWithTheSameKeyIsAppliedOnceAndAnotherAmountIsRefused() throws Exception {
        String loanId = disbursedLoan("5000.00");

        for (int i = 0; i < 2; i++) {
            mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                    .header("x-idempotency-key", "pay-1")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 100.00, \"currency\": \"AED\"}"))
                .andExpect(status().isOk());
        }
        mvc.perform(asCustomer(post("/api/v1/loans/{id}/payments", loanId))
                .header("x-idempotency-key", "pay-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 200.00, \"currency\": \"AED\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForMap("select count(*) as n, max(request_scope) as scope from sc_ln_loan_lifecycle.repayment where loan_id = ?", loanId))
            .containsEntry("n", 1L)
            .containsEntry("scope", CUSTOMER);
    }

    @Test
    void completedLoanPaymentEventIsAppliedOnceEvenWhenRedeliveredOrRepublished() throws Exception {
        String loanId = disbursedLoan("5000.00");
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments,
            new TransactionTemplate(transactionManager));

        listener.onLoanPaymentCompleted(record("6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11", "PAY-EVT-1", loanId));
        listener.onLoanPaymentCompleted(record("6f1c3a3e-1a52-4f7e-9d43-0b8a3d9f0c11", "PAY-EVT-1", loanId));
        listener.onLoanPaymentCompleted(record("0a3c6a43-7d1e-4b8a-9c1e-5b7f9f2d4e10", "PAY-EVT-1", loanId));

        assertThat(jdbc.queryForList("select payment_id from sc_ln_loan_lifecycle.repayment where loan_id = ?", String.class, loanId))
            .containsExactly("PAY-EVT-1");
        assertThat(jdbc.queryForObject("select source from sc_ln_loan_lifecycle.repayment where payment_id = 'PAY-EVT-1'", String.class))
            .isEqualTo("PAYMENT_EVENT");
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.inbox_message", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.repayment_allocation where payment_id = 'PAY-EVT-1'",
            Integer.class)).isPositive();
    }

    @Test
    void failedRepaymentLeavesNoInboxRowSoTheEventIsRetried() throws Exception {
        String loanId = disbursedLoan("1000.00");
        LoanPaymentCompletedListener listener = new LoanPaymentCompletedListener(json, new JdbcInbox(jdbc), repayments,
            new TransactionTemplate(transactionManager));
        ConsumerRecord<String, String> tooMuch = record("1b2c3d4e-0000-4000-8000-000000000001", "PAY-BIG", loanId)
            ;
        ConsumerRecord<String, String> overpaid = new ConsumerRecord<>(tooMuch.topic(), 0, 1L, tooMuch.key(),
            tooMuch.value().replace("\"250.00\"", "\"99999.00\""));

        assertThatThrownBy(() -> listener.onLoanPaymentCompleted(overpaid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.inbox_message", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sc_ln_loan_lifecycle.repayment", Integer.class)).isZero();
    }

    // --- persistence and outbox -----------------------------------------------

    @Test
    void loanWithScheduleRoundTripsThroughTheRepository() {
        Loan loan = Loan.createWithInstallments(LoanId.of("LOAN-SCHED-1"),
            com.bank.shared.kernel.domain.CustomerId.of(CUSTOMER),
            com.bank.shared.kernel.domain.Money.aed(new BigDecimal("12000.00")),
            com.bank.loan.domain.InterestRate.of(new BigDecimal("6.0")),
            com.bank.loan.domain.LoanTerm.ofMonths(12));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> loans.save(loan));

        Loan reloaded = new TransactionTemplate(transactionManager)
            .execute(tx -> loans.findById(LoanId.of("LOAN-SCHED-1")).orElseThrow());

        assertThat(reloaded.getInstallments()).hasSize(12);
        assertThat(reloaded.getTotalInstallmentAmount()).isEqualTo(loan.getTotalInstallmentAmount());
        assertThat(reloaded.getInstallments().getFirst().getInterestComponent()).isEqualTo(
            com.bank.shared.kernel.domain.Money.aed(new BigDecimal("60.00")));
        assertThat(reloaded.getInstallments().getFirst().getDueDate()).isEqualTo(loan.getApplicationDate().plusMonths(1));
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getDomainEvents()).isEmpty();
    }

    @Test
    void staleAggregateCannotOverwriteANewerVersion() throws Exception {
        String loanId = createLoan("5000.00", 12, "6.0");
        Loan stale = new TransactionTemplate(transactionManager).execute(tx -> loans.findById(LoanId.of(loanId)).orElseThrow());
        decisions.approve(LoanId.of(loanId));

        stale.cancel("customer withdrew");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> loans.save(stale)))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("select status from sc_ln_loan_lifecycle.loan where loan_id = ?", String.class, loanId))
            .isEqualTo("APPROVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesPendingEventsInOrderKeyedByLoanId() throws Exception {
        String loanId = createLoan("7000.00", 12, "6.0");
        decisions.approve(LoanId.of(loanId));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(35), Duration.ofDays(7));

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(2);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> records = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::topic)
            .containsExactly("evt.ln.loan.created.v1", "evt.ln.loan.approved.v1");
        assertThat(records.getAllValues()).extracting(ProducerRecord::key).containsOnly(loanId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonRowIsParkedAndDoesNotBlockLaterRows() throws Exception {
        String first = createLoan("7000.00", 12, "6.0");
        String second = createLoan("8000.00", 12, "6.0");
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return first.equals(record.key())
                ? CompletableFuture.failedFuture(new org.apache.kafka.common.errors.RecordTooLargeException("too large"))
                : CompletableFuture.completedFuture(null);
        });
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(35), Duration.ofDays(7), 2);

        relay.relayOnce();
        relay.relayOnce();
        relay.relayOnce();

        assertThat(outbox.countByParkedAtIsNotNull()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from sc_ln_loan_lifecycle.outbox_event where aggregate_id = ? and published_at is not null
            """, Integer.class, second)).isEqualTo(1);
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

    private String disbursedLoan(String amount) throws Exception {
        String loanId = createLoan(amount, 12, "6.0");
        mvc.perform(asOfficer(post("/api/v1/loans/{id}/approve", loanId))).andExpect(status().isOk());
        mvc.perform(asOfficer(post("/api/v1/loans/{id}/disburse", loanId))).andExpect(status().isOk());
        return loanId;
    }

    private String createLoan(String amount, int months, String rate) throws Exception {
        String body = mvc.perform(asCustomer(post("/api/v1/loans"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(loanBody(CUSTOMER, amount, months, rate)))
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("loanId").asText();
    }

    private static String loanBody(String customerId, String amount, int months, String rate) {
        return """
            {"customerId": "%s", "principalAmount": %s, "currency": "AED",
             "annualInterestRate": %s, "termInMonths": %d}
            """.formatted(customerId, amount, rate, months);
    }

    private static ConsumerRecord<String, String> record(String eventId, String paymentId, String loanId) {
        String value = """
            {"eventId":"%s","eventType":"Payments.Payment.LoanPaymentCompleted.v1",
             "occurredAt":"2026-10-08T06:00:00Z","aggregateId":"%s","aggregateVersion":3,"correlationId":"corr-it",
             "producer":"svc-pay-initiation-settlement",
             "data":{"paymentId":"%s","customerId":"%s","loanId":"%s",
                     "actualAmount":{"amount":"250.00","currency":"AED"},"transactionReference":"SETTLE-IT",
                     "completedAt":"2026-10-08T05:59:59Z"}}
            """.formatted(eventId, paymentId, paymentId, CUSTOMER, loanId);
        return new ConsumerRecord<>("evt.pay.payment.loan-payment-completed.v1", 0, 0L, paymentId, value);
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return as(CUSTOMER, "ROLE_CUSTOMER", request).header("x-fapi-interaction-id", "it-interaction-1");
    }

    private static MockHttpServletRequestBuilder asOfficer(MockHttpServletRequestBuilder request) {
        return as("officer-1", "ROLE_BANKER", request).header("x-fapi-interaction-id", "it-interaction-2");
    }

    /** Customers carry the customer_id claim (platform contract); staff and services do not. Subjects are UUIDs. */
    private static MockHttpServletRequestBuilder as(String subject, String role, MockHttpServletRequestBuilder request) {
        String uuid = java.util.UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return request.with(token(uuid, "ROLE_CUSTOMER".equals(role) ? subject : null, role));
    }

    /**
     * A token as SecurityConfiguration turns it into an authentication: the
     * name is the customer_id claim when present, else the subject (a UUID
     * under Keycloak).
     */
    static org.springframework.test.web.servlet.request.RequestPostProcessor token(String subject, String customerId, String role) {
        org.springframework.security.oauth2.jwt.Jwt.Builder builder = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "RS256").subject(subject);
        if (customerId != null) {
            builder.claim("customer_id", customerId);
        }
        org.springframework.security.oauth2.jwt.Jwt jwt = builder.build();
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,
                java.util.List.of(new SimpleGrantedAuthority(role)),
                com.bank.loan.infrastructure.config.SecurityConfiguration.principalName(jwt)));
    }
}
