package com.bank.loan.infrastructure.web;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CreditMovementRejectedException;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.application.IdempotencyKeyReusedException;
import com.bank.loan.application.InsufficientCreditException;
import com.bank.loan.application.LoanNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.RestClientException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps application and domain exceptions to the ErrorResponse shape of
 * loan-context.yaml. Messages are the domain's own; they carry ids, not
 * personal data.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String REPAYMENT_IDEMPOTENCY_CONSTRAINT = "uq_repayment_request_idempotency";

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "The caller may not access this loan");
    }

    /** The customer service did not answer; nothing was changed and the request can be retried. */
    @ExceptionHandler({CustomerCreditUnavailableException.class, RestClientException.class})
    ResponseEntity<ErrorResponse> dependencyUnavailable(RuntimeException ex) {
        log.warn("Customer service unavailable: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CUSTOMER_SERVICE_UNAVAILABLE",
            "Customer credit could not be checked; retry later");
    }

    /** Amounts in different currencies are never compared: an invalid request, not "insufficient credit". */
    @ExceptionHandler(CreditCurrencyMismatchException.class)
    ResponseEntity<ErrorResponse> currencyMismatch(CreditCurrencyMismatchException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", ex.getMessage());
    }

    /**
     * The customer service rejected the credit movement as invalid (its 400):
     * retrying the same request cannot succeed, so a 422, not a 503.
     */
    @ExceptionHandler(CreditMovementRejectedException.class)
    ResponseEntity<ErrorResponse> creditMovementRejected(CreditMovementRejectedException ex) {
        log.warn("Customer service rejected a credit movement: {}", ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "CREDIT_MOVEMENT_REJECTED",
            "The customer service rejected the credit movement for this loan");
    }

    /** The customer service does not know the customer; not a credit refusal. */
    @ExceptionHandler(CreditCustomerNotFoundException.class)
    ResponseEntity<ErrorResponse> customerNotFound(CreditCustomerNotFoundException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> idempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    /** Only the repayment idempotency race is a 409; any other integrity violation is a server fault. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrityViolation(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (detail.contains(REPAYMENT_IDEMPOTENCY_CONSTRAINT)) {
            return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "The same request is already being processed; retry");
        }
        log.error("Data integrity violation", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be stored");
    }

    @ExceptionHandler(LoanNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(LoanNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "LOAN_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ResponseEntity<ErrorResponse> insufficientCredit(InsufficientCreditException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_CREDIT", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> invalidTransition(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, "INVALID_LOAN_STATE", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> concurrentUpdate(OptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The loan was changed by another request; retry");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException invalid
            ? invalid.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request")
            : ex.getMessage();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /** Malformed JSON, an invalid header (x-idempotency-key over 128 characters) or path value. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HandlerMethodValidationException.class,
        ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> unreadableOrInvalid(Exception ex) {
        String message = ex instanceof HttpMessageNotReadableException
            ? "Request body is not valid JSON for this operation"
            : "Invalid request header or path value";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
