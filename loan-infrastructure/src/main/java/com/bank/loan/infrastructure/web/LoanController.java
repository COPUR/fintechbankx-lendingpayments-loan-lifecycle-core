package com.bank.loan.infrastructure.web;

import com.bank.loan.application.dto.CreateLoanRequest;
import com.bank.loan.application.dto.LoanResponse;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.in.LoanApplicationUseCase;
import com.bank.loan.domain.port.in.LoanDecisionUseCase;
import com.bank.loan.domain.port.in.LoanDisbursementUseCase;
import com.bank.loan.domain.port.in.LoanQueryUseCase;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RepayLoanCommand;
import com.bank.shared.kernel.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * HTTP adapter for the loan use cases (domain/port/in). FR-005 to FR-008.
 *
 * Role checks are declared per endpoint; ownership (a customer only sees and
 * changes their own loans) is enforced by {@link LoanAccessPolicy} after the
 * loan is found.
 */
@RestController
@Validated
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanApplicationUseCase applications;
    private final LoanDecisionUseCase decisions;
    private final LoanDisbursementUseCase disbursements;
    private final LoanRepaymentUseCase repayments;
    private final LoanQueryUseCase queries;

    public LoanController(LoanApplicationUseCase applications, LoanDecisionUseCase decisions,
                          LoanDisbursementUseCase disbursements, LoanRepaymentUseCase repayments,
                          LoanQueryUseCase queries) {
        this.applications = applications;
        this.decisions = decisions;
        this.disbursements = disbursements;
        this.repayments = repayments;
        this.queries = queries;
    }

    /** FR-005: apply for a loan. A customer applies only for themself. */
    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'ADMIN')")
    public ResponseEntity<LoanResponse> createLoanApplication(@Valid @RequestBody CreateLoanRequest request,
                                                              Authentication caller) {
        LoanAccessPolicy.requireActsFor(caller, request.customerId());
        Loan loan = applications.applyForLoan(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(LoanResponse.from(loan));
    }

    @GetMapping("/{loanId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'LOAN_OFFICER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<LoanResponse> getLoan(@PathVariable String loanId, Authentication caller) {
        Loan loan = queries.findLoan(LoanId.of(loanId));
        LoanAccessPolicy.requireActsFor(caller, loan.getCustomerId().getValue());
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    /** FR-006 */
    @PostMapping("/{loanId}/approve")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BANKER', 'ADMIN')")
    public ResponseEntity<LoanResponse> approveLoan(@PathVariable String loanId) {
        return ResponseEntity.ok(LoanResponse.from(decisions.approve(LoanId.of(loanId))));
    }

    /** FR-006 */
    @PostMapping("/{loanId}/reject")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BANKER', 'ADMIN')")
    public ResponseEntity<LoanResponse> rejectLoan(@PathVariable String loanId,
                                                   @Valid @RequestBody RejectLoanRequest request) {
        return ResponseEntity.ok(LoanResponse.from(decisions.reject(LoanId.of(loanId), request.reason())));
    }

    /** FR-007: reserves the customer's credit, then disburses. */
    @PostMapping("/{loanId}/disburse")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<LoanResponse> disburseLoan(@PathVariable String loanId) {
        return ResponseEntity.ok(LoanResponse.from(disbursements.disburse(LoanId.of(loanId))));
    }

    /**
     * FR-008: repayment, allocated to the schedule interest first. With
     * x-idempotency-key, a repeated request is applied once (key scope: the caller).
     */
    @PostMapping("/{loanId}/payments")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<LoanResponse> makePayment(
            @PathVariable String loanId,
            @Valid @RequestBody MakePaymentRequest request,
            @RequestHeader(name = "x-idempotency-key", required = false) @Size(max = 128) String idempotencyKey,
            Authentication caller) {
        LoanId id = LoanId.of(loanId);
        LoanAccessPolicy.requireActsFor(caller, queries.findLoan(id).getCustomerId().getValue());
        Money amount = Money.of(request.amount(), Currency.getInstance(request.currency()));
        Loan loan = repayments.repay(new RepayLoanCommand(id, amount, LoanAccessPolicy.idempotencyScope(caller), idempotencyKey));
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    /** A customer may cancel their own loan before disbursement. */
    @PostMapping("/{loanId}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'ADMIN')")
    public ResponseEntity<LoanResponse> cancelLoan(@PathVariable String loanId,
                                                   @Valid @RequestBody CancelLoanRequest request,
                                                   Authentication caller) {
        LoanId id = LoanId.of(loanId);
        LoanAccessPolicy.requireActsFor(caller, queries.findLoan(id).getCustomerId().getValue());
        return ResponseEntity.ok(LoanResponse.from(decisions.cancel(id, request.reason())));
    }

    public record RejectLoanRequest(@Size(max = 500) String reason) {}

    public record MakePaymentRequest(
        @NotNull(message = "Amount is required") @Positive(message = "Amount must be positive") BigDecimal amount,
        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be an ISO 4217 code") String currency) {}

    public record CancelLoanRequest(@Size(max = 500) String reason) {}
}
