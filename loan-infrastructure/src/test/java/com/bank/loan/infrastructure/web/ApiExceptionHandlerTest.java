package com.bank.loan.infrastructure.web;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.application.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void customerServiceOutagesAreA503WithAStableCode() {
        var unavailable = handler.dependencyUnavailable(new CustomerCreditUnavailableException("down"));
        var restClient = handler.dependencyUnavailable(new ResourceAccessException("timeout"));

        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody().code()).isEqualTo("CUSTOMER_SERVICE_UNAVAILABLE");
        assertThat(restClient.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(restClient.getBody().message()).doesNotContain("timeout");
    }

    @Test
    void currencyMismatchAndUnknownCustomerAre422WithTheirOwnCodes() {
        var mismatch = handler.currencyMismatch(new CreditCurrencyMismatchException(
            java.util.Currency.getInstance("USD"), java.util.Currency.getInstance("AED")));
        var unknown = handler.customerNotFound(new CreditCustomerNotFoundException("CUST-9"));

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatch.getBody().code()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(mismatch.getBody().message()).isEqualTo("The loan is in USD but the customer's credit is held in AED");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody().code()).isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    void unreadableBodiesAndInvalidHeadersAre400() {
        var unreadable = handler.unreadableOrInvalid(new org.springframework.http.converter.HttpMessageNotReadableException(
            "bad", new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));
        var header = handler.unreadableOrInvalid(new jakarta.validation.ConstraintViolationException("size", java.util.Set.of()));

        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unreadable.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(header.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void accessDeniedIsA403() {
        var response = handler.forbidden(new AccessDeniedException("x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void reusedIdempotencyKeyIsA422() {
        var response = handler.idempotencyKeyReused(new IdempotencyKeyReusedException("k-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void onlyTheRepaymentIdempotencyRaceIsA409() {
        var race = handler.integrityViolation(new DataIntegrityViolationException("dup",
            new SQLException("duplicate key value violates unique constraint \"uq_repayment_request_idempotency\"")));
        var other = handler.integrityViolation(new DataIntegrityViolationException("fk",
            new SQLException("violates foreign key constraint \"repayment_loan_id_fkey\"")));

        assertThat(race.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(race.getBody().code()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(other.getBody().message()).doesNotContain("repayment_loan_id_fkey");
    }

    @Test
    void domainAndApplicationErrorsKeepTheirCodes() {
        assertThat(handler.notFound(com.bank.loan.application.LoanNotFoundException.withId("L-1")).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.insufficientCredit(new com.bank.loan.application.InsufficientCreditException("no")).getBody().code())
            .isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(handler.invalidTransition(new IllegalStateException("x")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.concurrentUpdate(new org.springframework.dao.OptimisticLockingFailureException("v")).getBody().code())
            .isEqualTo("CONCURRENT_UPDATE");
        assertThat(handler.badRequest(new IllegalArgumentException("bad amount")).getBody().message()).isEqualTo("bad amount");
    }
}
