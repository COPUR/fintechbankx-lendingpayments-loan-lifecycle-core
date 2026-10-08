package com.bank.loan.infrastructure.external;

import com.bank.loan.application.LoanManagementService;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanStatus;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.port.out.LoanEventPublisher;
import com.bank.loan.domain.port.out.LoanRepository;
import com.bank.loan.domain.port.out.RepaymentLedger;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Review 5460235552 (comment 4221920604): the application service and the
 * real customer adapter together, with the failures ordered the way
 * production fails them. AED 25,000.00 is reserved, storing the
 * disbursement fails, storing the compensation intent fails too (so nothing
 * is released), and the loan is then cancelled. The cancellation must
 * release the held 25,000.00 under the compensation key, once.
 */
class CancelAfterFailedDisbursementTest {

    private static final LoanId LOAN = LoanId.of("LOAN-CANCEL-1");
    private static final CustomerId CUSTOMER = CustomerId.of("CUST-CANCEL-1");
    private static final Money PRINCIPAL = Money.aed(new BigDecimal("25000.00"));
    private static final String BASE = "http://customer/api/v1/customers/CUST-CANCEL-1/credit";
    private static final String POSITION = """
        {"customerId":"CUST-CANCEL-1","currency":"AED","creditLimit":100000.00,"usedCredit":25000.00,"availableCredit":75000.00}
        """;

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://customer")
        .requestInterceptor((request, body, execution) -> {
            calls.add(request.getMethod() + " " + request.getURI().getPath().replace("/api/v1/customers/CUST-CANCEL-1", "")
                + " " + request.getHeaders().getFirst("x-idempotency-key") + " "
                + new String(body, StandardCharsets.UTF_8).replaceAll(".*\"amount\":([0-9.]+).*", "$1"));
            return execution.execute(request, body);
        });
    private boolean timeoutNextReserve;
    private final MockRestServiceServer server = stubCustomerService(builder);
    private final CustomerProfileHttpAdapterTest.Generations generations = new CustomerProfileHttpAdapterTest.Generations();
    private final CustomerProfileHttpAdapter adapter = new CustomerProfileHttpAdapter(builder.build(), () -> "service-token",
        Currency.getInstance("AED"), generations, 3);
    private final Loans loans = new Loans();
    private final LoanManagementService service = new LoanManagementService(loans, adapter, mock(LoanEventPublisher.class),
        mock(RepaymentLedger.class), TransactionOperations.withoutTransaction(),
        Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC));

    @Test
    void cancellingAfterAFailedDisbursementReleasesTheHeldReservationUnderTheCompensationKeyOnce() {
        loans.approved(LOAN);
        loans.failNextSave = true;
        generations.failGenerationWrites = true;

        assertThatThrownBy(() -> service.disburse(LOAN)).isInstanceOf(OptimisticLockingFailureException.class);
        // 25,000.00 is held by the customer service; the compensation intent was not stored, so nothing was released.
        assertThat(calls).containsExactly("POST /credit/reserve LOAN-CANCEL-1:reserve 25000.00");
        assertThat(loans.status(LOAN)).isEqualTo(LoanStatus.APPROVED);

        generations.failGenerationWrites = false;
        Loan cancelled = service.cancel(LOAN, "Customer withdrew");

        assertThat(cancelled.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(calls).containsExactly(
            "POST /credit/reserve LOAN-CANCEL-1:reserve 25000.00",
            "POST /credit/release LOAN-CANCEL-1:reserve:compensation 25000.00");

        assertThatThrownBy(() -> service.cancel(LOAN, "Asked twice")).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasSize(2);
    }

    /**
     * The reserve timed out, so whether 25,000.00 is held is unknown; the
     * customer service would subtract a release from other loans' credit
     * (customer #13 CreditProfile.releaseCredit). The cancellation sends
     * nothing and leaves the row for the recovery sweep and an operator.
     */
    @Test
    void cancellingAfterAReserveWithoutAnAnswerSendsNoRelease() {
        LoanId loanId = LoanId.of("LOAN-CANCEL-2");
        loans.approved(loanId);
        timeoutNextReserve = true;

        assertThatThrownBy(() -> service.disburse(loanId))
            .isInstanceOf(com.bank.loan.domain.port.out.CustomerCreditUnavailableException.class);
        service.cancel(loanId, "Customer withdrew");

        assertThat(calls).containsExactly("POST /credit/reserve LOAN-CANCEL-2:reserve 25000.00");
        assertThat(generations.state(loanId)).isEqualTo(ReservationGenerations.State.RESERVING);
    }

    private MockRestServiceServer stubCustomerService(RestClient.Builder builder) {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/reserve")).andRespond(request -> {
            if (timeoutNextReserve) {
                timeoutNextReserve = false;
                throw new java.net.SocketTimeoutException("Read timed out");
            }
            return withSuccess(POSITION, MediaType.APPLICATION_JSON).createResponse(request);
        });
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/release"))
            .andRespond(withSuccess(POSITION, MediaType.APPLICATION_JSON));
        return server;
    }

    /** Loans by status, rebuilt on every read the way the database would return them. */
    static final class Loans implements LoanRepository {
        private final Map<LoanId, LoanStatus> statuses = new HashMap<>();
        boolean failNextSave;

        void approved(LoanId loanId) {
            statuses.put(loanId, LoanStatus.APPROVED);
        }

        LoanStatus status(LoanId loanId) {
            return statuses.get(loanId);
        }

        @Override
        public Optional<Loan> findById(LoanId loanId) {
            LoanStatus status = statuses.get(loanId);
            if (status == null) {
                return Optional.empty();
            }
            Loan loan = Loan.create(loanId, CUSTOMER, PRINCIPAL, InterestRate.of(new BigDecimal("6.5")), LoanTerm.ofMonths(24));
            loan.approve();
            switch (status) {
                case APPROVED -> { }
                case CANCELLED -> loan.cancel("stored");
                case DISBURSED -> loan.disburse();
                default -> throw new IllegalStateException("not modelled: " + status);
            }
            loan.clearDomainEvents();
            return Optional.of(loan);
        }

        @Override
        public Loan save(Loan loan) {
            if (failNextSave) {
                failNextSave = false;
                throw new OptimisticLockingFailureException("loan changed meanwhile");
            }
            statuses.put(loan.getId(), loan.getStatus());
            return loan;
        }

        @Override
        public List<Loan> findByCustomerId(CustomerId customerId) {
            return List.of();
        }

        @Override
        public List<Loan> findByStatus(LoanStatus status) {
            return List.of();
        }

        @Override
        public boolean existsById(LoanId loanId) {
            return statuses.containsKey(loanId);
        }

        @Override
        public void delete(Loan loan) {
            statuses.remove(loan.getId());
        }

        @Override
        public List<Loan> findOverdueLoans() {
            return List.of();
        }
    }
}
