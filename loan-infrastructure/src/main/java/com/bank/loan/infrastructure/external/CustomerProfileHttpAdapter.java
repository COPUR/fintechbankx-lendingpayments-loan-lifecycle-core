package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.domain.LoanId;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti-corruption adapter to the customer-profile-kyc service, which owns
 * credit limits and reservations (svc-cus-profile-kyc, customer-context.yaml):
 * GET /credit for the credit position (CustomerCreditResponse), POST
 * /credit/reserve and /credit/release for movements (paths are configuration,
 * {@link Paths}). Only the credit figures are read; no customer personal data
 * passes through this service.
 *
 * <ul>
 *   <li>Idempotency keys are derived from the loan: {@code {loanId}:reserve}
 *       ({@code :g{n}} after n cancelled reservations), {@code {loanId}:release},
 *       and {@code {reserveKey}:compensation} to cancel a reservation; the
 *       body's {@code reference} is the loan id. A cancellation stores its
 *       intent before releasing, and an unconfirmed one is re-sent before
 *       the next reservation ({@link ReservationGenerations}).</li>
 *   <li>409 CONCURRENT_UPDATE / DUPLICATE_REQUEST are retried with the same
 *       key, at most {@code maxAttempts} times, then unavailable.</li>
 *   <li>422 INSUFFICIENT_CREDIT is the only refusal
 *       ({@link CreditDecision#REFUSED}); 422 CURRENCY_MISMATCH is
 *       {@link CreditCurrencyMismatchException}; 404 is
 *       {@link CreditCustomerNotFoundException}; anything else (400, 401,
 *       403, other 422, 409 IDEMPOTENCY_KEY_REUSED, 5xx, timeout, no service
 *       token) is {@link CustomerCreditUnavailableException}.</li>
 *   <li>Currency: the credit position's {@code currency} is used; only if the
 *       provider leaves it out is the configured ledger currency assumed. A
 *       different currency is {@link CreditCurrencyMismatchException}, never
 *       "insufficient credit".</li>
 * </ul>
 *
 * No transaction is involved: the application calls this port outside its
 * database transactions, and cancels a reservation itself when storing the
 * disbursement fails.
 */
public class CustomerProfileHttpAdapter implements CustomerCreditService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileHttpAdapter.class);
    static final String INTERACTION_ID_HEADER = "x-fapi-interaction-id";
    static final String IDEMPOTENCY_KEY_HEADER = "x-idempotency-key";
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

    private final RestClient restClient;
    private final Supplier<String> bearerToken;
    private final Currency ledgerCurrency;
    private final ReservationGenerations generations;
    private final int maxAttempts;
    private final Paths paths;

    /** Provider paths; {customerId} is expanded. */
    public record Paths(String creditPosition, String reserve, String release) {
        public static final Paths DEFAULT = new Paths(
            "/api/v1/customers/{customerId}/credit",
            "/api/v1/customers/{customerId}/credit/reserve",
            "/api/v1/customers/{customerId}/credit/release");
    }

    public CustomerProfileHttpAdapter(RestClient restClient, Supplier<String> bearerToken, Currency ledgerCurrency,
                                      ReservationGenerations generations, int maxAttempts) {
        this(restClient, bearerToken, ledgerCurrency, generations, maxAttempts, Paths.DEFAULT);
    }

    public CustomerProfileHttpAdapter(RestClient restClient, Supplier<String> bearerToken, Currency ledgerCurrency,
                                      ReservationGenerations generations, int maxAttempts, Paths paths) {
        this.paths = paths;
        this.restClient = restClient;
        this.bearerToken = bearerToken;
        this.ledgerCurrency = ledgerCurrency;
        this.generations = generations;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public boolean hasAvailableCredit(CustomerId customerId, Money amount) {
        CreditPosition position = creditPosition(customerId);
        if (position.availableCredit() == null) {
            return false;
        }
        Currency creditCurrency = creditCurrency(position);
        if (!amount.getCurrency().equals(creditCurrency)) {
            throw new CreditCurrencyMismatchException(amount.getCurrency(), creditCurrency);
        }
        return Money.of(position.availableCredit(), creditCurrency).compareTo(amount) >= 0;
    }

    @Override
    public Money getAvailableCredit(CustomerId customerId) {
        CreditPosition position = creditPosition(customerId);
        Currency currency = creditCurrency(position);
        return position.availableCredit() == null ? Money.zero(currency) : Money.of(position.availableCredit(), currency);
    }

    /**
     * A compensation that was started but not confirmed is re-sent first,
     * under its own key (the provider replays a release it already applied);
     * while it cannot be confirmed the reservation fails instead of replaying
     * the compensated reservation's key.
     */
    @Override
    public CreditDecision reserveCredit(LoanId loanId, CustomerId customerId, Money amount) {
        OptionalInt pending = generations.pendingCompensation(loanId);
        if (pending.isPresent()) {
            int compensated = pending.getAsInt();
            CreditDecision undone = moveCredit(loanId, customerId, amount, paths.release(), compensationKey(loanId, compensated));
            if (undone != CreditDecision.ACCEPTED) {
                throw new CustomerCreditUnavailableException("Customer service did not confirm the release of the "
                    + "compensated reservation " + reserveKey(loanId, compensated) + "; not reserving again");
            }
            generations.compensationDone(loanId, compensated);
        }
        return moveCredit(loanId, customerId, amount, paths.reserve(), reserveKey(loanId, generations.current(loanId)));
    }

    /**
     * Stores the intent (generation n+1, compensation of n pending) in its own
     * transaction before sending the release under generation n's
     * compensation key. If the intent cannot be stored nothing is released,
     * so the reservation and its key stay valid for a retry.
     */
    @Override
    public CreditDecision cancelReservation(LoanId loanId, CustomerId customerId, Money amount) {
        int generation = generations.current(loanId);
        try {
            generations.beginCompensation(loanId, generation);
        } catch (RuntimeException notStored) {
            throw new CustomerCreditUnavailableException("Could not record the compensation of "
                + reserveKey(loanId, generation) + "; reservation kept, nothing released", notStored);
        }
        CreditDecision undone = moveCredit(loanId, customerId, amount, paths.release(), compensationKey(loanId, generation));
        if (undone == CreditDecision.ACCEPTED) {
            try {
                generations.compensationDone(loanId, generation);
            } catch (RuntimeException notRecorded) {
                log.warn("Release {} was accepted but not recorded; the next reservation re-sends it under the same key",
                    compensationKey(loanId, generation), notRecorded);
            }
        }
        return undone;
    }

    @Override
    public CreditDecision releaseCredit(LoanId loanId, CustomerId customerId, Money amount) {
        return moveCredit(loanId, customerId, amount, paths.release(), loanId.getValue() + ":release");
    }

    private static String reserveKey(LoanId loanId, int generation) {
        return loanId.getValue() + ":reserve" + (generation == 0 ? "" : ":g" + generation);
    }

    private static String compensationKey(LoanId loanId, int generation) {
        return reserveKey(loanId, generation) + ":compensation";
    }

    private CreditPosition creditPosition(CustomerId customerId) {
        try {
            CreditPosition position = restClient.get()
                .uri(paths.creditPosition(), customerId.getValue())
                .headers(this::addCallerHeaders)
                .retrieve()
                .body(CreditPosition.class);
            if (position == null) {
                throw new CustomerCreditUnavailableException("Customer service sent no credit position for "
                    + customerId.getValue());
            }
            return position;
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new CreditCustomerNotFoundException(customerId.getValue());
        } catch (RestClientException unavailable) {
            throw new CustomerCreditUnavailableException("Customer service could not report the credit of "
                + customerId.getValue(), unavailable);
        }
    }

    private Currency creditCurrency(CreditPosition position) {
        if (position.currency() == null || position.currency().isBlank()) {
            return ledgerCurrency;
        }
        try {
            return Currency.getInstance(position.currency());
        } catch (IllegalArgumentException unknown) {
            throw new CustomerCreditUnavailableException("Customer service reported an unknown currency "
                + position.currency(), unknown);
        }
    }

    private CreditDecision moveCredit(LoanId loanId, CustomerId customerId, Money amount, String movement,
                                      String idempotencyKey) {
        for (int attempt = 1; ; attempt++) {
            try {
                CreditPosition position = restClient.post()
                    .uri(movement, customerId.getValue())
                    .headers(this::addCallerHeaders)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(new CreditMovementRequest(amount.getAmount(), amount.getCurrency().getCurrencyCode(), loanId.getValue()))
                    .retrieve()
                    .body(CreditPosition.class);
                if (position != null) {
                    log.debug("Credit {} {} accepted; customer {} has {} available", movement, idempotencyKey,
                        customerId.getValue(), position.availableCredit());
                }
                return CreditDecision.ACCEPTED;
            } catch (HttpClientErrorException error) {
                int status = error.getStatusCode().value();
                String code = errorCode(error);
                if (status == HttpStatus.CONFLICT.value() && isRetryable(code)) {
                    if (attempt < maxAttempts) {
                        log.info("Customer service reported {} on credit {} {}; retrying with the same key", code, movement, idempotencyKey);
                        continue;
                    }
                    throw new CustomerCreditUnavailableException("Customer service kept reporting " + code
                        + " for credit " + movement + " " + idempotencyKey, error);
                }
                if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "INSUFFICIENT_CREDIT".equals(code)) {
                    log.info("Customer service refused credit {} {} for {}: {}", movement, idempotencyKey,
                        customerId.getValue(), code);
                    return CreditDecision.REFUSED;
                }
                if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "CURRENCY_MISMATCH".equals(code)) {
                    throw new CreditCurrencyMismatchException(amount.getCurrency(), null);
                }
                if (status == HttpStatus.NOT_FOUND.value()) {
                    throw new CreditCustomerNotFoundException(customerId.getValue());
                }
                throw new CustomerCreditUnavailableException("Customer service answered " + status + " " + code
                    + " to credit " + movement + " " + idempotencyKey, error);
            } catch (RestClientException unavailable) {
                throw new CustomerCreditUnavailableException("Customer service unavailable for credit " + movement
                    + " " + idempotencyKey, unavailable);
            }
        }
    }

    private static boolean isRetryable(String code) {
        return "CONCURRENT_UPDATE".equals(code) || "DUPLICATE_REQUEST".equals(code);
    }

    private static String errorCode(HttpClientErrorException error) {
        Matcher matcher = ERROR_CODE.matcher(error.getResponseBodyAsString());
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Service token (never the end user's) and the interaction id; no token means no call. */
    private void addCallerHeaders(HttpHeaders headers) {
        String token;
        try {
            token = bearerToken.get();
        } catch (RuntimeException noToken) {
            throw new CustomerCreditUnavailableException("No service token for the customer service", noToken);
        }
        if (token == null || token.isBlank()) {
            throw new CustomerCreditUnavailableException("No service token for the customer service");
        }
        headers.setBearerAuth(token);
        String interactionId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.set(INTERACTION_ID_HEADER, interactionId != null ? interactionId : UUID.randomUUID().toString());
    }

    /** reference: the loan the credit is reserved for. */
    record CreditMovementRequest(BigDecimal amount, String currency, String reference) {
    }

    /**
     * The credit position svc-cus-profile-kyc returns from GET /credit and from
     * the reserve and release calls: customerId, creditLimit, usedCredit,
     * availableCredit, currency. Any other field is ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreditPosition(String customerId, BigDecimal creditLimit, BigDecimal usedCredit,
                          BigDecimal availableCredit, String currency) {
    }
}
