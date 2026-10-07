package com.bank.loan.infrastructure.web;

import com.bank.loan.application.InsufficientCreditException;
import com.bank.loan.application.LoanNotFoundException;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
