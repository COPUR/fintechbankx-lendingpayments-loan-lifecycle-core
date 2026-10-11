package com.bank.loan.application;

import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.PaymentResult;
import com.bank.loan.domain.Repayment;
import com.bank.loan.domain.port.in.ApplyForLoanCommand;
import com.bank.loan.domain.port.in.LoanApplicationUseCase;
import com.bank.loan.domain.port.in.LoanDecisionUseCase;
import com.bank.loan.domain.port.in.LoanDisbursementUseCase;
import com.bank.loan.domain.port.in.LoanQueryUseCase;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RecordCompletedLoanPaymentCommand;
import com.bank.loan.domain.port.in.RepayLoanCommand;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditService.CreditDecision;
import com.bank.loan.domain.port.out.CustomerCreditService.UnusedReservation;
import com.bank.loan.domain.port.out.EventCausation;
import com.bank.loan.domain.port.out.LoanEventPublisher;
import com.bank.loan.domain.port.out.LoanRepository;
import com.bank.loan.domain.port.out.RepaymentLedger;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Application service implementing the loan use cases (domain/port/in).
 *
 * Database work runs in short transactions ({@link TransactionOperations});
 * calls to the customer service run outside them, so no database connection
 * is held while waiting on another service:
 * <ul>
 *   <li>apply: credit check, then create and save;</li>
 *   <li>disburse: read, reserve credit, then reload-disburse-save (which also
 *       marks the reservation used); if storing fails the reservation is
 *       cancelled again (unless another request disbursed the loan
 *       meanwhile);</li>
 *   <li>cancel and reject: change-save; after that commits, a reservation
 *       this service recorded as accepted for the loan (a failed or abandoned
 *       disbursement) is released under its compensation key;</li>
 *   <li>repay: allocate-save-record in one transaction; when the loan is fully
 *       paid the credit is released after that transaction commits.</li>
 * </ul>
 * Each transaction saves the aggregate and hands its events to the outbox.
 *
 * Functional requirements: FR-005 application, FR-006 approval, FR-007
 * disbursement, FR-008 repayment.
 */
@Service("loanService")
public class LoanManagementService implements LoanApplicationUseCase, LoanDecisionUseCase,
        LoanDisbursementUseCase, LoanRepaymentUseCase, LoanQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoanManagementService.class);

    private final LoanRepository loanRepository;
    private final CustomerCreditService customerCreditService;
    private final LoanEventPublisher eventPublisher;
    private final RepaymentLedger repaymentLedger;
    private final TransactionOperations transactions;
    private final Clock clock;

    public LoanManagementService(LoanRepository loanRepository,
                                 CustomerCreditService customerCreditService,
                                 LoanEventPublisher eventPublisher,
                                 RepaymentLedger repaymentLedger,
                                 TransactionOperations transactions,
                                 Clock clock) {
        this.loanRepository = loanRepository;
        this.customerCreditService = customerCreditService;
        this.eventPublisher = eventPublisher;
        this.repaymentLedger = repaymentLedger;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Loan applyForLoan(ApplyForLoanCommand command) {
        if (!customerCreditService.hasAvailableCredit(command.customerId(), command.principal())) {
            Money availableCredit = customerCreditService.getAvailableCredit(command.customerId());
            throw InsufficientCreditException.forCustomer(command.customerId().getValue(),
                command.principal().toString(), availableCredit.toString());
        }
        Loan loan = Loan.create(LoanId.generate(), command.customerId(), command.principal(),
            command.annualRate(), command.term());
        return inTransaction(() -> saveAndPublish(loan));
    }

    @Override
    public Loan approve(LoanId loanId) {
        return inTransaction(() -> {
            Loan loan = load(loanId);
            loan.approve();
            return saveAndPublish(loan);
        });
    }

    @Override
    public Loan reject(LoanId loanId, String reason) {
        return inTransaction(() -> {
            Loan loan = load(loanId);
            loan.reject(reason);
            Loan saved = saveAndPublish(loan);
            afterCommit(() -> releaseUnusedReservation(saved));
            return saved;
        });
    }

    @Override
    public Loan cancel(LoanId loanId, String reason) {
        return inTransaction(() -> {
            Loan loan = load(loanId);
            loan.cancel(reason);
            Loan saved = saveAndPublish(loan);
            afterCommit(() -> releaseUnusedReservation(saved));
            return saved;
        });
    }

    /**
     * Review 5460235552: a loan closed before disbursement may still hold
     * credit reserved by a failed or abandoned disbursement. The adapter
     * releases it only if it recorded the reservation as accepted, so a
     * release never frees credit held by other loans. The closure has
     * committed and stands whatever happens here; an unconfirmed release
     * stays pending and the recovery sweep re-sends it under the same key.
     */
    private void releaseUnusedReservation(Loan loan) {
        try {
            UnusedReservation outcome = customerCreditService.releaseUnusedReservation(
                loan.getId(), loan.getCustomerId(), loan.getPrincipalAmount());
            if (outcome == UnusedReservation.RELEASED) {
                log.info("Loan {} was closed with credit still reserved; the reservation was released",
                    loan.getId().getValue());
            } else if (outcome == UnusedReservation.UNCONFIRMED) {
                log.warn("Loan {} was closed while a reservation for it is unconfirmed; left for the recovery sweep",
                    loan.getId().getValue());
            }
        } catch (RuntimeException failure) {
            log.error("Loan {} was closed but its reserved credit could not be released yet; the recovery sweep re-sends it",
                loan.getId().getValue(), failure);
        }
    }

    /**
     * Credit is reserved before the loan changes. A refusal leaves the loan
     * APPROVED and raises no LoanDisbursed. If the disbursement cannot be
     * stored after a successful reservation, the reservation is cancelled.
     */
    @Override
    public Loan disburse(LoanId loanId) {
        Loan approved = findLoan(loanId);
        approved.ensureCanBeDisbursed();
        CreditDecision reservation = customerCreditService.reserveCredit(
            approved.getId(), approved.getCustomerId(), approved.getPrincipalAmount());
        if (reservation != CreditDecision.ACCEPTED) {
            throw new InsufficientCreditException(String.format(
                "Customer service refused to reserve %s for the loan", approved.getPrincipalAmount()));
        }
        try {
            return inTransaction(() -> {
                Loan loan = load(loanId);
                loan.disburse();
                Loan disbursed = saveAndPublish(loan);
                // Same transaction: fails (and rolls the disbursement back) if the reservation was released meanwhile.
                customerCreditService.markReservationUsed(loanId);
                return disbursed;
            });
        } catch (RuntimeException failure) {
            cancelReservationUnlessDisbursed(approved, failure);
            throw failure;
        }
    }

    private void cancelReservationUnlessDisbursed(Loan approved, RuntimeException failure) {
        try {
            if (!findLoan(approved.getId()).getStatus().isNeverDisbursed()) {
                // Another request disbursed the loan; the reservation (same key) is its reservation.
                return;
            }
            // Still APPROVED, or closed meanwhile: nothing uses the reservation. The adapter
            // sends nothing if the closure already released it.
            CreditDecision undone = customerCreditService.cancelReservation(
                approved.getId(), approved.getCustomerId(), approved.getPrincipalAmount());
            if (undone != CreditDecision.ACCEPTED) {
                log.error("Disbursement of loan {} failed and the customer service refused to cancel its reservation",
                    approved.getId().getValue());
            }
        } catch (RuntimeException cancelFailure) {
            failure.addSuppressed(cancelFailure);
            log.error("Disbursement of loan {} failed and its reservation could not be cancelled; a retry reuses it",
                approved.getId().getValue(), cancelFailure);
        }
    }

    /**
     * With an idempotency key, a repeat of the same request returns the loan
     * without applying the money twice; the same key for a different loan or
     * amount is refused.
     */
    @Override
    public Loan repay(RepayLoanCommand command) {
        return inTransaction(() -> {
            if (command.hasIdempotencyKey()) {
                Optional<Repayment> earlier = repaymentLedger.findByIdempotencyKey(command.requestScope(), command.idempotencyKey());
                if (earlier.isPresent()) {
                    if (!earlier.get().loanId().equals(command.loanId()) || !earlier.get().amount().equals(command.amount())) {
                        throw new IdempotencyKeyReusedException(command.idempotencyKey());
                    }
                    return load(command.loanId());
                }
            }
            return applyRepayment(load(command.loanId()), PaymentId.generate(), command.amount(), Repayment.Source.API,
                command.requestScope(), command.idempotencyKey(), null);
        });
    }

    /**
     * Joins the caller's transaction when there is one (the consumer's inbox
     * insert), so the inbox row, the loan and the repayment commit together.
     */
    @Override
    public boolean recordCompletedLoanPayment(RecordCompletedLoanPaymentCommand command) {
        return inTransaction(() -> {
            if (repaymentLedger.contains(command.paymentId())) {
                log.info("Repayment {} for loan {} was already applied; skipping",
                    command.paymentId().getValue(), command.loanId().getValue());
                return false;
            }
            applyRepayment(load(command.loanId()), command.paymentId(), command.amount(), Repayment.Source.PAYMENT_EVENT,
                null, null, command.causation());
            return true;
        });
    }

    @Override
    public Loan findLoan(LoanId loanId) {
        return inTransaction(() -> load(loanId));
    }

    private Loan applyRepayment(Loan loan, PaymentId paymentId, Money amount, Repayment.Source source,
                                String requestScope, String idempotencyKey, EventCausation causation) {
        PaymentResult result = loan.makePayment(paymentId, amount);
        Loan saved = saveAndPublish(loan, causation);
        repaymentLedger.record(Repayment.of(result, amount, Instant.now(clock), source, requestScope, idempotencyKey));
        if (result.isLoanFullyPaid()) {
            afterCommit(() -> releaseCredit(saved));
        }
        return saved;
    }

    /**
     * Runs once the surrounding transaction has committed; without one
     * (never the case in this service) it runs at once.
     */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * The repayment has committed and stands whatever happens here. The
     * release is idempotent ({loanId}:release), so a failed release can be
     * re-sent; until then the customer's credit stays reserved.
     */
    private void releaseCredit(Loan loan) {
        try {
            CreditDecision release = customerCreditService.releaseCredit(
                loan.getId(), loan.getCustomerId(), loan.getPrincipalAmount());
            if (release != CreditDecision.ACCEPTED) {
                log.error("Customer service refused to release the credit of fully paid loan {}", loan.getId().getValue());
            }
        } catch (RuntimeException failure) {
            log.error("Credit of fully paid loan {} could not be released; re-send release {}:release",
                loan.getId().getValue(), loan.getId().getValue(), failure);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private Loan load(LoanId loanId) {
        return loanRepository.findById(loanId).orElseThrow(() -> LoanNotFoundException.withId(loanId.getValue()));
    }

    /**
     * Saves the aggregate and hands its pending events to the outbox port
     * inside the same transaction, then clears them from the aggregate.
     */
    private Loan saveAndPublish(Loan loan) {
        return saveAndPublish(loan, null);
    }

    /**
     * As {@link #saveAndPublish(Loan)}; with a causation (a consumed message)
     * the events carry its correlationId and causationId (ADR-019 section 4).
     */
    private Loan saveAndPublish(Loan loan, EventCausation causation) {
        List<DomainEvent> events = List.copyOf(loan.getDomainEvents());
        Loan savedLoan = loanRepository.save(loan);
        if (!events.isEmpty()) {
            if (causation == null) {
                eventPublisher.publish(savedLoan, events);
            } else {
                eventPublisher.publish(savedLoan, events, causation);
            }
        }
        loan.clearDomainEvents();
        return savedLoan;
    }
}
