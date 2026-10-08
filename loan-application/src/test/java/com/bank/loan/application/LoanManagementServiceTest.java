package com.bank.loan.application;

import com.bank.loan.application.dto.CreateLoanRequest;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.LoanRepository;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.LoanApprovedEvent;
import com.bank.loan.domain.LoanCreatedEvent;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.bank.loan.domain.port.in.RepayLoanCommand;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.domain.LoanStatus;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.Repayment;
import com.bank.loan.domain.port.out.LoanEventPublisher;
import com.bank.loan.domain.port.out.RepaymentLedger;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanManagementServiceTest {

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private CustomerCreditService customerCreditService;

    @Mock
    private LoanEventPublisher eventPublisher;

    private final InMemoryRepaymentLedger ledger = new InMemoryRepaymentLedger();
    private final FakeTransactions transactions = new FakeTransactions();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC);
    private LoanManagementService service;

    @BeforeEach
    void setUp() {
        service = new LoanManagementService(loanRepository, customerCreditService, eventPublisher, ledger, transactions, clock);
    }

    @Test
    void createLoanApplicationShouldPersistWhenCreditIsAvailable() {
        CreateLoanRequest request = new CreateLoanRequest(
            "CUST-LOAN-001",
            new BigDecimal("25000.00"),
            "AED",
            new BigDecimal("6.5"),
            24
        );
        when(customerCreditService.hasAvailableCredit(any(CustomerId.class), any(Money.class))).thenReturn(true);
        when(loanRepository.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Loan created = service.applyForLoan(request.toCommand());

        assertThat(created.getCustomerId().getValue()).isEqualTo("CUST-LOAN-001");
        assertThat(created.getStatus()).isEqualTo(LoanStatus.CREATED);
        assertThat(created.getInstallments()).hasSize(24);
        verify(loanRepository).save(any(Loan.class));
    }

    @Test
    void createLoanApplicationShouldRejectWhenCreditIsInsufficient() {
        CreateLoanRequest request = new CreateLoanRequest(
            "CUST-LOAN-002",
            new BigDecimal("25000.00"),
            "AED",
            new BigDecimal("6.5"),
            24
        );
        when(customerCreditService.hasAvailableCredit(any(CustomerId.class), any(Money.class))).thenReturn(false);
        when(customerCreditService.getAvailableCredit(any(CustomerId.class))).thenReturn(Money.aed(new BigDecimal("1000.00")));

        assertThatThrownBy(() -> service.applyForLoan(request.toCommand()))
            .isInstanceOf(InsufficientCreditException.class)
            .hasMessageContaining("insufficient credit");

        verify(loanRepository, never()).save(any(Loan.class));
    }

    @Test
    void approveRejectAndCancelShouldTransitionAndPersist() {
        Loan loanToApprove = loan("LOAN-SVC-001", "12000.00", 12);
        when(loanRepository.findById(LoanId.of("LOAN-SVC-001"))).thenReturn(Optional.of(loanToApprove));
        when(loanRepository.save(loanToApprove)).thenReturn(loanToApprove);

        assertThat(service.approve(LoanId.of("LOAN-SVC-001")).getStatus()).isEqualTo(LoanStatus.APPROVED);

        Loan loanToReject = loan("LOAN-SVC-002", "12000.00", 12);
        when(loanRepository.findById(LoanId.of("LOAN-SVC-002"))).thenReturn(Optional.of(loanToReject));
        when(loanRepository.save(loanToReject)).thenReturn(loanToReject);
        assertThat(service.reject(LoanId.of("LOAN-SVC-002"), "Policy").getStatus()).isEqualTo(LoanStatus.REJECTED);

        Loan loanToCancel = loan("LOAN-SVC-003", "12000.00", 12);
        when(loanRepository.findById(LoanId.of("LOAN-SVC-003"))).thenReturn(Optional.of(loanToCancel));
        when(loanRepository.save(loanToCancel)).thenReturn(loanToCancel);
        assertThat(service.cancel(LoanId.of("LOAN-SVC-003"), "Customer request").getStatus()).isEqualTo(LoanStatus.CANCELLED);
    }

    @Test
    void disburseShouldReserveCreditAndPersist() {
        Loan approvedLoan = loan("LOAN-SVC-004", "10000.00", 12);
        approvedLoan.approve();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-004"))).thenReturn(Optional.of(approvedLoan));
        when(loanRepository.save(approvedLoan)).thenReturn(approvedLoan);
        when(customerCreditService.reserveCredit(approvedLoan.getId(), approvedLoan.getCustomerId(), approvedLoan.getPrincipalAmount()))
            .thenReturn(CreditDecision.ACCEPTED);

        Loan disbursed = service.disburse(LoanId.of("LOAN-SVC-004"));

        assertThat(disbursed.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        verify(customerCreditService).reserveCredit(approvedLoan.getId(), approvedLoan.getCustomerId(), approvedLoan.getPrincipalAmount());
        verify(loanRepository).save(approvedLoan);
    }

    @Test
    void refusedReservationLeavesTheLoanApprovedWithoutSavingOrEvents() {
        Loan approvedLoan = loan("LOAN-SVC-REF", "10000.00", 12);
        approvedLoan.approve();
        approvedLoan.clearDomainEvents();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-REF"))).thenReturn(Optional.of(approvedLoan));
        when(customerCreditService.reserveCredit(any(), any(), any())).thenReturn(CreditDecision.REFUSED);

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-REF")))
            .isInstanceOf(InsufficientCreditException.class)
            .hasMessageContaining("refused to reserve AED 10000.00 for loan LOAN-SVC-REF");

        assertThat(approvedLoan.getStatus()).isEqualTo(LoanStatus.APPROVED);
        assertThat(approvedLoan.getDomainEvents()).isEmpty();
        verify(loanRepository, never()).save(any(Loan.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unavailableCustomerServiceFailsTheDisbursementWithoutChangingTheLoan() {
        Loan approvedLoan = loan("LOAN-SVC-503", "10000.00", 12);
        approvedLoan.approve();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-503"))).thenReturn(Optional.of(approvedLoan));
        when(customerCreditService.reserveCredit(any(), any(), any()))
            .thenThrow(new CustomerCreditUnavailableException("customer service down"));

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-503")))
            .isInstanceOf(CustomerCreditUnavailableException.class);
        assertThat(approvedLoan.getStatus()).isEqualTo(LoanStatus.APPROVED);
        verify(loanRepository, never()).save(any(Loan.class));
    }

    @Test
    void disbursingALoanThatIsNotApprovedNeverReservesCredit() {
        Loan created = loan("LOAN-SVC-NEW", "10000.00", 12);
        when(loanRepository.findById(LoanId.of("LOAN-SVC-NEW"))).thenReturn(Optional.of(created));

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-NEW")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(customerCreditService);
    }

    @Test
    void makePaymentShouldReleaseCreditOnlyWhenLoanBecomesFullyPaid() {
        // 5,000.00 at 6% over 12 months: schedule total 5,163.99
        Loan fullPaymentLoan = loan("LOAN-SVC-005", "5000.00", 12);
        fullPaymentLoan.approve();
        fullPaymentLoan.disburse();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-005"))).thenReturn(Optional.of(fullPaymentLoan));
        when(loanRepository.save(fullPaymentLoan)).thenReturn(fullPaymentLoan);
        when(customerCreditService.releaseCredit(any(), any(), any())).thenReturn(CreditDecision.ACCEPTED);

        Loan fullyPaid = service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-005"), fullPaymentLoan.getOutstandingBalance()));

        assertThat(fullyPaid.getStatus()).isEqualTo(LoanStatus.FULLY_PAID);
        assertThat(fullyPaid.getOutstandingBalance().getAmount()).isEqualByComparingTo("0.00");
        verify(customerCreditService).releaseCredit(fullPaymentLoan.getId(), fullPaymentLoan.getCustomerId(), fullPaymentLoan.getPrincipalAmount());
        assertThat(ledger.recorded).singleElement().satisfies(repayment -> {
            assertThat(repayment.loanFullyPaid()).isTrue();
            assertThat(repayment.allocations()).hasSize(12);
            assertThat(repayment.source()).isEqualTo(Repayment.Source.API);
            assertThat(repayment.appliedAt()).isEqualTo(Instant.parse("2026-10-08T06:00:00Z"));
        });

        Loan partialPaymentLoan = loan("LOAN-SVC-006", "6000.00", 12);
        partialPaymentLoan.approve();
        partialPaymentLoan.disburse();
        Money scheduleTotal = partialPaymentLoan.getOutstandingBalance();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-006"))).thenReturn(Optional.of(partialPaymentLoan));
        when(loanRepository.save(partialPaymentLoan)).thenReturn(partialPaymentLoan);

        Loan partial = service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-006"), Money.aed(new BigDecimal("1000.00"))));

        assertThat(partial.getOutstandingBalance().getAmount()).isEqualByComparingTo(scheduleTotal.getAmount().subtract(new BigDecimal("1000.00")));
        verify(customerCreditService, never()).releaseCredit(
            eq(partialPaymentLoan.getId()),
            eq(partialPaymentLoan.getCustomerId()),
            eq(partialPaymentLoan.getPrincipalAmount())
        );
    }

    @Test
    void refusedReleaseDoesNotUndoTheRepayment() {
        Loan loan = disbursedLoan("LOAN-SVC-REL");
        when(customerCreditService.releaseCredit(any(), any(), any())).thenReturn(CreditDecision.REFUSED);

        Loan paid = service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-REL"), loan.getOutstandingBalance()));

        assertThat(paid.getStatus()).isEqualTo(LoanStatus.FULLY_PAID);
        assertThat(ledger.recorded).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyReturnsTheLoanWithoutApplyingTheMoneyTwice() {
        Loan loan = disbursedLoan("LOAN-SVC-IDEM");
        RepayLoanCommand request = new RepayLoanCommand(LoanId.of("LOAN-SVC-IDEM"), Money.aed(new BigDecimal("100.00")),
            "CUST-SVC-IDEM", "key-1");

        service.repay(request);
        Loan replay = service.repay(request);

        assertThat(ledger.recorded).hasSize(1);
        assertThat(ledger.recorded.getFirst().idempotencyKey()).isEqualTo("key-1");
        assertThat(replay.getOutstandingBalance()).isEqualTo(loan.getOutstandingBalance());
        verify(loanRepository).save(loan);
    }

    @Test
    void reusingAnIdempotencyKeyForAnotherAmountIsRefused() {
        disbursedLoan("LOAN-SVC-IDEM2");
        service.repay(new RepayLoanCommand(LoanId.of("LOAN-SVC-IDEM2"), Money.aed(new BigDecimal("100.00")), "CUST-SVC-IDEM2", "key-2"));

        assertThatThrownBy(() -> service.repay(new RepayLoanCommand(LoanId.of("LOAN-SVC-IDEM2"),
                Money.aed(new BigDecimal("200.00")), "CUST-SVC-IDEM2", "key-2")))
            .isInstanceOf(IdempotencyKeyReusedException.class)
            .hasMessageContaining("key-2");
        assertThat(ledger.recorded).hasSize(1);
    }

    @Test
    void completedLoanPaymentIsAppliedOnceUnderThePaymentServicesId() {
        Loan loan = disbursedLoan("LOAN-SVC-EVT");
        Money before = loan.getOutstandingBalance();

        RecordCompletedLoanPaymentCommand completed = new RecordCompletedLoanPaymentCommand(PaymentId.of("PAY-EVT-1"),
            LoanId.of("LOAN-SVC-EVT"), Money.aed(new BigDecimal("250.00")));
        boolean first = service.recordCompletedLoanPayment(completed);
        boolean second = service.recordCompletedLoanPayment(completed);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(loan.getOutstandingBalance()).isEqualTo(before.subtract(Money.aed(new BigDecimal("250.00"))));
        assertThat(ledger.recorded).singleElement().satisfies(repayment -> {
            assertThat(repayment.paymentId()).isEqualTo(PaymentId.of("PAY-EVT-1"));
            assertThat(repayment.source()).isEqualTo(Repayment.Source.PAYMENT_EVENT);
            assertThat(repayment.idempotencyKey()).isNull();
        });
    }

    @Test
    void completedLoanPaymentForAnUnknownLoanFails() {
        when(loanRepository.findById(any(LoanId.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordCompletedLoanPayment(new RecordCompletedLoanPaymentCommand(
                PaymentId.of("PAY-X"), LoanId.of("LOAN-NONE"), Money.aed(BigDecimal.TEN))))
            .isInstanceOf(LoanNotFoundException.class);
    }

    // --- no database transaction around customer-service calls ------------------

    @Test
    void creditCheckAndReservationAreAnsweredWithNoTransactionOpen() {
        Loan approvedLoan = loan("LOAN-SVC-NOTX", "10000.00", 12);
        approvedLoan.approve();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-NOTX"))).thenReturn(Optional.of(approvedLoan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<Boolean> transactionOpenAtCall = new ArrayList<>();
        when(customerCreditService.hasAvailableCredit(any(), any())).thenAnswer(invocation -> {
            transactionOpenAtCall.add(transactions.active);
            return true;
        });
        when(customerCreditService.reserveCredit(any(), any(), any())).thenAnswer(invocation -> {
            transactionOpenAtCall.add(transactions.active);
            return CreditDecision.ACCEPTED;
        });

        service.applyForLoan(new com.bank.loan.domain.port.in.ApplyForLoanCommand(CustomerId.of("CUST-NOTX"),
            Money.aed(new BigDecimal("1000.00")), InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12)));
        service.disburse(LoanId.of("LOAN-SVC-NOTX"));

        assertThat(transactionOpenAtCall).containsExactly(false, false);
        assertThat(transactions.commits).isEqualTo(3); // save application; read; disburse
    }

    @Test
    void reservationIsCancelledWhenTheDisbursementCannotBeStored() {
        Loan approvedLoan = loan("LOAN-SVC-COMP", "10000.00", 12);
        approvedLoan.approve();
        Loan stillApproved = loan("LOAN-SVC-COMP", "10000.00", 12);
        stillApproved.approve();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-COMP")))
            .thenReturn(Optional.of(approvedLoan), Optional.of(approvedLoan), Optional.of(stillApproved));
        when(customerCreditService.reserveCredit(any(), any(), any())).thenReturn(CreditDecision.ACCEPTED);
        when(loanRepository.save(any(Loan.class))).thenThrow(new OptimisticLockingFailureException("version 3"));
        when(customerCreditService.cancelReservation(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(transactions.active).isFalse();
            return CreditDecision.ACCEPTED;
        });

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-COMP")))
            .isInstanceOf(OptimisticLockingFailureException.class);

        verify(customerCreditService).cancelReservation(approvedLoan.getId(), approvedLoan.getCustomerId(),
            Money.aed(new BigDecimal("10000.00")));
        assertThat(transactions.rollbacks).isEqualTo(1);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reservationIsKeptWhenAConcurrentRequestDisbursedTheLoan() {
        Loan approvedLoan = loan("LOAN-SVC-RACE", "10000.00", 12);
        approvedLoan.approve();
        Loan disbursedElsewhere = loan("LOAN-SVC-RACE", "10000.00", 12);
        disbursedElsewhere.approve();
        disbursedElsewhere.disburse();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-RACE")))
            .thenReturn(Optional.of(approvedLoan), Optional.of(disbursedElsewhere), Optional.of(disbursedElsewhere));
        when(customerCreditService.reserveCredit(any(), any(), any())).thenReturn(CreditDecision.ACCEPTED);

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-RACE")))
            .isInstanceOf(IllegalStateException.class);

        verify(customerCreditService, never()).cancelReservation(any(), any(), any());
    }

    @Test
    void failedCancellationIsAttachedToTheOriginalFailure() {
        Loan approvedLoan = loan("LOAN-SVC-COMP2", "10000.00", 12);
        approvedLoan.approve();
        Loan stillApproved = loan("LOAN-SVC-COMP2", "10000.00", 12);
        stillApproved.approve();
        when(loanRepository.findById(LoanId.of("LOAN-SVC-COMP2")))
            .thenReturn(Optional.of(approvedLoan), Optional.of(approvedLoan), Optional.of(stillApproved));
        when(customerCreditService.reserveCredit(any(), any(), any())).thenReturn(CreditDecision.ACCEPTED);
        when(loanRepository.save(any(Loan.class))).thenThrow(new OptimisticLockingFailureException("version 3"));
        when(customerCreditService.cancelReservation(any(), any(), any()))
            .thenThrow(new CustomerCreditUnavailableException("down"));

        assertThatThrownBy(() -> service.disburse(LoanId.of("LOAN-SVC-COMP2")))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                .isInstanceOf(CustomerCreditUnavailableException.class));
    }

    @Test
    void creditIsReleasedOnlyAfterTheFullRepaymentCommitted() {
        Loan loan = disbursedLoan("LOAN-SVC-AFTER");
        when(customerCreditService.releaseCredit(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(transactions.active).isFalse();
            assertThat(transactions.commits).isEqualTo(1);
            assertThat(ledger.recorded).hasSize(1);
            return CreditDecision.ACCEPTED;
        });

        service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-AFTER"), loan.getOutstandingBalance()));

        var order = inOrder(loanRepository, customerCreditService);
        order.verify(loanRepository).save(loan);
        order.verify(customerCreditService).releaseCredit(loan.getId(), loan.getCustomerId(), loan.getPrincipalAmount());
    }

    @Test
    void noReleaseWhenTheFullRepaymentLosesAnOptimisticLock() {
        Loan loan = disbursedLoan("LOAN-SVC-LOCK");
        when(loanRepository.save(loan)).thenThrow(new OptimisticLockingFailureException("version 2"));

        assertThatThrownBy(() -> service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-LOCK"),
                loan.getOutstandingBalance())))
            .isInstanceOf(OptimisticLockingFailureException.class);

        verify(customerCreditService, never()).releaseCredit(any(), any(), any());
        assertThat(ledger.recorded).isEmpty();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void failedReleaseDoesNotFailTheCommittedRepayment() {
        Loan loan = disbursedLoan("LOAN-SVC-RELFAIL");
        when(customerCreditService.releaseCredit(any(), any(), any()))
            .thenThrow(new CustomerCreditUnavailableException("down"));

        Loan paid = service.repay(RepayLoanCommand.withoutKey(LoanId.of("LOAN-SVC-RELFAIL"), loan.getOutstandingBalance()));

        assertThat(paid.getStatus()).isEqualTo(LoanStatus.FULLY_PAID);
        assertThat(ledger.recorded).hasSize(1);
    }

    @Test
    void completedPaymentJoinsTheCallersTransactionAndReleasesAfterItCommits() {
        Loan loan = disbursedLoan("LOAN-SVC-JOIN");
        when(customerCreditService.releaseCredit(any(), any(), any())).thenReturn(CreditDecision.ACCEPTED);
        RecordCompletedLoanPaymentCommand completed = new RecordCompletedLoanPaymentCommand(PaymentId.of("PAY-JOIN"),
            LoanId.of("LOAN-SVC-JOIN"), loan.getOutstandingBalance());

        transactions.execute(status -> {
            service.recordCompletedLoanPayment(completed);
            verify(customerCreditService, never()).releaseCredit(any(), any(), any());
            return null;
        });

        assertThat(transactions.commits).isEqualTo(1);
        verify(customerCreditService).releaseCredit(loan.getId(), loan.getCustomerId(), loan.getPrincipalAmount());
    }

    /**
     * Stands in for Spring's TransactionTemplate: nested calls join the outer
     * transaction, synchronizations run afterCommit once the outermost commits.
     */
    static final class FakeTransactions implements TransactionOperations {
        boolean active;
        int commits;
        int rollbacks;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            if (active) {
                return action.doInTransaction(null);
            }
            active = true;
            TransactionSynchronizationManager.initSynchronization();
            T result;
            List<TransactionSynchronization> synchronizations;
            try {
                result = action.doInTransaction(null);
                synchronizations = TransactionSynchronizationManager.getSynchronizations();
            } catch (RuntimeException failure) {
                rollbacks++;
                throw failure;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                active = false;
            }
            commits++;
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            return result;
        }
    }

    private Loan disbursedLoan(String loanId) {
        Loan loan = loan(loanId, "5000.00", 12);
        loan.approve();
        loan.disburse();
        loan.clearDomainEvents();
        org.mockito.Mockito.lenient().when(loanRepository.findById(LoanId.of(loanId))).thenReturn(Optional.of(loan));
        org.mockito.Mockito.lenient().when(loanRepository.save(loan)).thenReturn(loan);
        return loan;
    }

    private static final class InMemoryRepaymentLedger implements RepaymentLedger {
        final List<Repayment> recorded = new ArrayList<>();

        @Override
        public boolean contains(PaymentId paymentId) {
            return recorded.stream().anyMatch(r -> r.paymentId().equals(paymentId));
        }

        @Override
        public Optional<Repayment> findByIdempotencyKey(String requestScope, String idempotencyKey) {
            return recorded.stream()
                .filter(r -> requestScope.equals(r.requestScope()) && idempotencyKey.equals(r.idempotencyKey()))
                .findFirst();
        }

        @Override
        public void record(Repayment repayment) {
            recorded.add(repayment);
        }
    }

    @Test
    void findAndGetCustomerIdShouldReturnLoanData() {
        Loan loan = loan("LOAN-SVC-007", "9000.00", 12);
        when(loanRepository.findById(LoanId.of("LOAN-SVC-007"))).thenReturn(Optional.of(loan));

        Loan found = service.findLoan(LoanId.of("LOAN-SVC-007"));

        assertThat(found).isSameAs(loan);
    }

    @Test
    void methodsShouldThrowLoanNotFoundWhenMissing() {
        when(loanRepository.findById(any(LoanId.class))).thenReturn(Optional.empty());

        LoanId missing = LoanId.of("MISSING");
        assertThatThrownBy(() -> service.approve(missing)).isInstanceOf(LoanNotFoundException.class);
        assertThatThrownBy(() -> service.reject(missing, "reason")).isInstanceOf(LoanNotFoundException.class);
        assertThatThrownBy(() -> service.disburse(missing)).isInstanceOf(LoanNotFoundException.class);
        assertThatThrownBy(() -> service.repay(RepayLoanCommand.withoutKey(missing, Money.aed(new BigDecimal("1.00")))))
            .isInstanceOf(LoanNotFoundException.class);
        assertThatThrownBy(() -> service.findLoan(missing)).isInstanceOf(LoanNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(missing, "reason")).isInstanceOf(LoanNotFoundException.class);
    }

    @Test
    void createLoanApplicationShouldPassExpectedArgumentsToCreditService() {
        CreateLoanRequest request = new CreateLoanRequest(
            "CUST-LOAN-008",
            new BigDecimal("30000.00"),
            "AED",
            new BigDecimal("8.0"),
            36
        );
        when(customerCreditService.hasAvailableCredit(any(CustomerId.class), any(Money.class))).thenReturn(true);
        when(loanRepository.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyForLoan(request.toCommand());

        ArgumentCaptor<CustomerId> customerIdCaptor = ArgumentCaptor.forClass(CustomerId.class);
        ArgumentCaptor<Money> moneyCaptor = ArgumentCaptor.forClass(Money.class);
        verify(customerCreditService).hasAvailableCredit(customerIdCaptor.capture(), moneyCaptor.capture());
        assertThat(customerIdCaptor.getValue().getValue()).isEqualTo("CUST-LOAN-008");
        assertThat(moneyCaptor.getValue().getAmount()).isEqualByComparingTo("30000.00");
    }

    private static Loan loan(String loanId, String amount, int termMonths) {
        return Loan.create(
            LoanId.of(loanId),
            CustomerId.of("CUST-" + loanId.substring(5)),
            Money.aed(new BigDecimal(amount)),
            InterestRate.of(new BigDecimal("6.0")),
            LoanTerm.ofMonths(termMonths)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void createLoanApplicationShouldHandCreatedEventToOutboxAndClearIt() {
        CreateLoanRequest request = new CreateLoanRequest(
            "CUST-LOAN-EVT",
            new BigDecimal("25000.00"),
            "AED",
            new BigDecimal("6.5"),
            24
        );
        when(customerCreditService.hasAvailableCredit(any(CustomerId.class), any(Money.class))).thenReturn(true);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        when(loanRepository.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyForLoan(request.toCommand());

        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publish(eq(saved.getValue()), events.capture());
        assertThat(events.getValue()).singleElement().isInstanceOf(LoanCreatedEvent.class);
        assertThat(saved.getValue().getDomainEvents()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void approveLoanShouldPublishOnlyTheApprovalEvent() {
        Loan loan = Loan.create(LoanId.of("LOAN-EVT-2"), CustomerId.of("CUST-EVT-2"),
            Money.aed(new BigDecimal("12000.00")), InterestRate.of(new BigDecimal("5.0")), LoanTerm.ofMonths(12));
        loan.clearDomainEvents();
        when(loanRepository.findById(LoanId.of("LOAN-EVT-2"))).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(LoanId.of("LOAN-EVT-2"));

        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publish(eq(loan), events.capture());
        assertThat(events.getValue()).singleElement().isInstanceOf(LoanApprovedEvent.class);
    }
}
