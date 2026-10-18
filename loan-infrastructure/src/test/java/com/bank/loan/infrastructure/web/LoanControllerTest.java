package com.bank.loan.infrastructure.web;

import com.bank.loan.application.dto.CreateLoanRequest;
import com.bank.loan.application.dto.LoanResponse;
import com.bank.loan.domain.InterestRate;
import com.bank.loan.domain.Loan;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.LoanTerm;
import com.bank.loan.domain.port.in.ApplyForLoanCommand;
import com.bank.loan.domain.port.in.LoanApplicationUseCase;
import com.bank.loan.domain.port.in.LoanDecisionUseCase;
import com.bank.loan.domain.port.in.LoanDisbursementUseCase;
import com.bank.loan.domain.port.in.LoanQueryUseCase;
import com.bank.loan.domain.port.in.LoanRepaymentUseCase;
import com.bank.loan.domain.port.in.RepayLoanCommand;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanControllerTest {

    @Mock private LoanApplicationUseCase applications;
    @Mock private LoanDecisionUseCase decisions;
    @Mock private LoanDisbursementUseCase disbursements;
    @Mock private LoanRepaymentUseCase repayments;
    @Mock private LoanQueryUseCase queries;

    private LoanController controller;

    // customer_id claim identifies the end user; sub is the identity-provider user id
    private static final Authentication OWNER = token("user-1", "CUST-CTRL-001", "ROLE_CUSTOMER");
    private static final Authentication OTHER_CUSTOMER = token("user-2", "CUST-OTHER", "ROLE_CUSTOMER");
    private static final Authentication CUSTOMER_WITHOUT_CLAIM = token("CUST-CTRL-001", null, "ROLE_CUSTOMER");
    private static final Authentication BANKER = token("banker-1", null, "ROLE_BANKER");
    private static final Authentication SERVICE = token("service-account-svc-pay", null, "ROLE_SERVICE");

    private static Authentication token(String subject, String customerId, String role) {
        org.springframework.security.oauth2.jwt.Jwt.Builder jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "RS256").subject(subject);
        if (customerId != null) {
            jwt.claim("customer_id", customerId);
        }
        org.springframework.security.oauth2.jwt.Jwt built = jwt.build();
        return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(built,
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)),
            com.bank.loan.infrastructure.config.SecurityConfiguration.principalName(built));
    }

    @BeforeEach
    void setUp() {
        controller = new LoanController(applications, decisions, disbursements, repayments, queries);
    }

    @Test
    void customerAppliesForThemselfAndGetsTheCreatedLoan() {
        CreateLoanRequest request = new CreateLoanRequest("CUST-CTRL-001", new BigDecimal("12000.00"), "AED",
            new BigDecimal("6.0"), 12);
        when(applications.applyForLoan(any(ApplyForLoanCommand.class))).thenReturn(loan("LOAN-CTRL-001"));

        ResponseEntity<LoanResponse> entity = controller.createLoanApplication(request, OWNER);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entity.getBody().loanId()).isEqualTo("LOAN-CTRL-001");
        assertThat(entity.getBody().monthlyPayment()).isEqualByComparingTo("1032.80");
        ArgumentCaptor<ApplyForLoanCommand> command = ArgumentCaptor.forClass(ApplyForLoanCommand.class);
        verify(applications).applyForLoan(command.capture());
        assertThat(command.getValue().principal()).isEqualTo(Money.aed(new BigDecimal("12000.00")));
    }

    @Test
    void customerCannotApplyForSomeoneElse() {
        CreateLoanRequest request = new CreateLoanRequest("CUST-SOMEONE-ELSE", new BigDecimal("12000.00"), "AED",
            new BigDecimal("6.0"), 12);

        assertThatThrownBy(() -> controller.createLoanApplication(request, OWNER))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(applications);
        when(applications.applyForLoan(any(ApplyForLoanCommand.class))).thenReturn(loan("LOAN-CTRL-009"));
        assertThat(controller.createLoanApplication(request, BANKER).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ownerStaffAndServicesReadALoanButAnotherCustomerGets403() {
        when(queries.findLoan(LoanId.of("LOAN-CTRL-002"))).thenReturn(loan("LOAN-CTRL-002"));

        assertThat(controller.getLoan("LOAN-CTRL-002", OWNER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getLoan("LOAN-CTRL-002", BANKER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getLoan("LOAN-CTRL-002", SERVICE).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThatThrownBy(() -> controller.getLoan("LOAN-CTRL-002", OTHER_CUSTOMER))
            .isInstanceOf(AccessDeniedException.class);
        // sub happens to equal the loan's customer id, but only the customer_id claim counts
        assertThatThrownBy(() -> controller.getLoan("LOAN-CTRL-002", CUSTOMER_WITHOUT_CLAIM))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void decisionsAndDisbursementGoToTheirUseCases() {
        Loan approved = loan("LOAN-CTRL-003");
        approved.approve();
        when(decisions.approve(LoanId.of("LOAN-CTRL-003"))).thenReturn(approved);
        Loan rejected = loan("LOAN-CTRL-004");
        rejected.reject("Policy");
        when(decisions.reject(LoanId.of("LOAN-CTRL-004"), "Policy")).thenReturn(rejected);
        Loan disbursed = loan("LOAN-CTRL-005");
        disbursed.approve();
        disbursed.disburse();
        when(disbursements.disburse(LoanId.of("LOAN-CTRL-005"))).thenReturn(disbursed);

        assertThat(controller.approveLoan("LOAN-CTRL-003").getBody().status()).isEqualTo("APPROVED");
        assertThat(controller.rejectLoan("LOAN-CTRL-004", new LoanController.RejectLoanRequest("Policy")).getBody().status())
            .isEqualTo("REJECTED");
        assertThat(controller.disburseLoan("LOAN-CTRL-005").getBody().status()).isEqualTo("DISBURSED");
    }

    @Test
    void ownerCancelsButAnotherCustomerGets403() {
        Loan loan = loan("LOAN-CTRL-006");
        when(queries.findLoan(LoanId.of("LOAN-CTRL-006"))).thenReturn(loan);
        Loan cancelled = loan("LOAN-CTRL-006");
        cancelled.cancel("Changed mind");
        when(decisions.cancel(LoanId.of("LOAN-CTRL-006"), "Changed mind")).thenReturn(cancelled);

        assertThatThrownBy(() -> controller.cancelLoan("LOAN-CTRL-006",
                new LoanController.CancelLoanRequest("Changed mind"), OTHER_CUSTOMER))
            .isInstanceOf(AccessDeniedException.class);
        verify(decisions, never()).cancel(any(), any());

        assertThat(controller.cancelLoan("LOAN-CTRL-006", new LoanController.CancelLoanRequest("Changed mind"), OWNER)
            .getBody().status()).isEqualTo("CANCELLED");
    }

    @Test
    void paymentCarriesTheCallerScopedIdempotencyKey() {
        Loan loan = loan("LOAN-CTRL-007");
        when(queries.findLoan(LoanId.of("LOAN-CTRL-007"))).thenReturn(loan);
        when(repayments.repay(any(RepayLoanCommand.class))).thenReturn(loan);

        controller.makePayment("LOAN-CTRL-007", new LoanController.MakePaymentRequest(new BigDecimal("100.00"), "AED"),
            "key-7", OWNER);
        controller.makePayment("LOAN-CTRL-007", new LoanController.MakePaymentRequest(new BigDecimal("100.00"), "AED"),
            null, SERVICE);

        ArgumentCaptor<RepayLoanCommand> commands = ArgumentCaptor.forClass(RepayLoanCommand.class);
        verify(repayments, org.mockito.Mockito.times(2)).repay(commands.capture());
        assertThat(commands.getAllValues().get(0)).isEqualTo(new RepayLoanCommand(LoanId.of("LOAN-CTRL-007"),
            Money.aed(new BigDecimal("100.00")), "CUST-CTRL-001", "key-7"));
        assertThat(commands.getAllValues().get(1).hasIdempotencyKey()).isFalse();
    }

    @Test
    void anotherCustomerCannotPayIntoTheLoan() {
        when(queries.findLoan(LoanId.of("LOAN-CTRL-008"))).thenReturn(loan("LOAN-CTRL-008"));

        assertThatThrownBy(() -> controller.makePayment("LOAN-CTRL-008",
                new LoanController.MakePaymentRequest(new BigDecimal("100.00"), "AED"), null, OTHER_CUSTOMER))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repayments);
    }

    private static Loan loan(String loanId) {
        return Loan.create(LoanId.of(loanId), CustomerId.of("CUST-CTRL-001"), Money.aed(new BigDecimal("12000.00")),
            InterestRate.of(new BigDecimal("6.0")), LoanTerm.ofMonths(12));
    }
}
