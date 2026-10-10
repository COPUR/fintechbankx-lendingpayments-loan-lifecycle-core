package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CreditMovementRejectedException;
import com.bank.loan.domain.port.out.CreditReservationNeedsOperatorException;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.domain.port.out.CustomerCreditUnavailableException;
import com.bank.loan.domain.LoanId;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Optional;
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
 *       body's {@code reference} is the loan id. A reservation is recorded
 *       (RESERVING) before it is sent and marked RESERVED once accepted; a
 *       cancellation stores its intent before releasing, and an unconfirmed
 *       one is re-sent before the next reservation, by the cancellation of
 *       the loan, or by the recovery sweep ({@link ReservationGenerations},
 *       {@link CreditReservationSweep}).</li>
 *   <li>A release is sent only for a reservation known to be accepted: the
 *       customer service subtracts a release without checking that a
 *       reservation for the reference exists (customer #13
 *       CreditProfile.releaseCredit), so a blind release would free credit
 *       held by the customer's other loans. Customer release by reference
 *       (provider contract pending, codes RESERVATION_NOT_FOUND and
 *       RELEASE_EXCEEDS_RESERVATION) is mapped in {@code sendCompensation};
 *       the release names the reservation with the same reference (the
 *       loan id) it was reserved with.</li>
 *   <li>409 CONCURRENT_UPDATE / DUPLICATE_REQUEST are retried with the same
 *       key, at most {@code maxAttempts} times, then unavailable.</li>
 *   <li>422 INSUFFICIENT_CREDIT is the only refusal
 *       ({@link CreditDecision#REFUSED}); 422 CURRENCY_MISMATCH is
 *       {@link CreditCurrencyMismatchException}; 404 is
 *       {@link CreditCustomerNotFoundException}; 400 is
 *       {@link CreditMovementRejectedException} on a movement (non-retryable,
 *       422 in loan); a loan whose compensating release was refused with
 *       RELEASE_EXCEEDS_RESERVATION is {@link CreditReservationNeedsOperatorException}
 *       (non-retryable, 409 in loan) until an operator resolves it;
 *       anything else (400 on the position read, 401, 403, other 422, 409
 *       IDEMPOTENCY_KEY_REUSED, 5xx, timeout, no service token) is
 *       {@link CustomerCreditUnavailableException}.</li>
 *   <li>Currency: the credit position's {@code currency} is used; only if the
 *       provider leaves it out is the configured ledger currency assumed. A
 *       different currency is {@link CreditCurrencyMismatchException}, never
 *       "insufficient credit".</li>
 * </ul>
 *
 * No transaction of the caller is involved, except in
 * {@link #markReservationUsed}, which joins the disbursement transaction: the
 * application calls the other methods outside its database transactions, and
 * cancels a reservation itself when storing the disbursement fails.
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
    private final Counter unmatchedReleases;

    /** Customer release-by-reference codes (provider contract pending, see the class comment). */
    static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    static final String RELEASE_EXCEEDS_RESERVATION = "RELEASE_EXCEEDS_RESERVATION";
    static final String UNMATCHED_RELEASES = "loan.credit.releases.unmatched";

    /** What became of a compensating release. */
    private enum Compensation { RELEASED, NOTHING_HELD, REFUSED }

    /** 422 from the customer service on a release that names the loan's reservation. */
    static final class ReleaseRefusedException extends CustomerCreditUnavailableException {
        private final String code;

        ReleaseRefusedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

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
        this(restClient, bearerToken, ledgerCurrency, generations, maxAttempts, paths, new SimpleMeterRegistry());
    }

    public CustomerProfileHttpAdapter(RestClient restClient, Supplier<String> bearerToken, Currency ledgerCurrency,
                                      ReservationGenerations generations, int maxAttempts, Paths paths,
                                      MeterRegistry meters) {
        this.unmatchedReleases = Counter.builder(UNMATCHED_RELEASES)
            .description("Compensating releases the customer service answered RESERVATION_NOT_FOUND: nothing was reserved")
            .register(meters);
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
            if (sendCompensation(loanId, customerId, amount, compensated) == Compensation.REFUSED) {
                throw new CustomerCreditUnavailableException("Customer service did not confirm the release of the "
                    + "compensated reservation " + reserveKey(loanId, compensated) + "; not reserving again");
            }
        }
        int generation = generations.current(loanId);
        try {
            generations.beginReservation(loanId, generation);
        } catch (RuntimeException notStored) {
            throw new CustomerCreditUnavailableException("Could not record the reservation "
                + reserveKey(loanId, generation) + " before sending it; nothing reserved", notStored);
        }
        // An exception here leaves the row RESERVING: whether the provider applied it is unknown.
        CreditDecision decision = moveCredit(loanId, customerId, amount, paths.reserve(), reserveKey(loanId, generation));
        try {
            generations.reservationAnswered(loanId, generation, decision == CreditDecision.ACCEPTED);
        } catch (RuntimeException notRecorded) {
            log.warn("Reservation {} was answered {} but the answer was not recorded; the row stays RESERVING",
                reserveKey(loanId, generation), decision, notRecorded);
        }
        return decision;
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
        boolean claimed;
        try {
            claimed = generations.beginCompensation(loanId, generation);
        } catch (RuntimeException notStored) {
            throw new CustomerCreditUnavailableException("Could not record the compensation of "
                + reserveKey(loanId, generation) + "; reservation kept, nothing released", notStored);
        }
        if (!claimed) {
            log.info("Reservation {} is no longer outstanding (released or used meanwhile); nothing to cancel",
                reserveKey(loanId, generation));
            return CreditDecision.ACCEPTED;
        }
        return sendCompensation(loanId, customerId, amount, generation) == Compensation.REFUSED
            ? CreditDecision.REFUSED : CreditDecision.ACCEPTED;
    }

    /**
     * Re-sends a pending compensation, then compensates a reservation
     * recorded as RESERVED. A reservation still RESERVING or UNCONFIRMED is
     * left alone: it may never have been applied, and the customer service
     * would subtract a release of it from other loans' credit.
     */
    @Override
    public UnusedReservation releaseUnusedReservation(LoanId loanId, CustomerId customerId, Money amount) {
        UnusedReservation outcome = UnusedReservation.NONE;
        OptionalInt pending = generations.pendingCompensation(loanId);
        if (pending.isPresent()) {
            outcome = released(sendCompensation(loanId, customerId, amount, pending.getAsInt()), loanId, pending.getAsInt());
        }
        Optional<ReservationGenerations.Reservation> row = generations.find(loanId);
        if (row.isEmpty() || row.get().state() == null || row.get().state() == ReservationGenerations.State.USED) {
            return outcome;
        }
        int generation = row.get().generation();
        if (row.get().state() != ReservationGenerations.State.RESERVED) {
            log.warn("Reservation {} is {}: not known to be applied, so it is not released",
                reserveKey(loanId, generation), row.get().state());
            return UnusedReservation.UNCONFIRMED;
        }
        if (!generations.beginCompensationOfAccepted(loanId, generation)) {
            return outcome;
        }
        return released(sendCompensation(loanId, customerId, amount, generation), loanId, generation);
    }

    private static UnusedReservation released(Compensation compensation, LoanId loanId, int generation) {
        return switch (compensation) {
            case RELEASED -> UnusedReservation.RELEASED;
            case NOTHING_HELD -> UnusedReservation.NONE;
            case REFUSED -> throw new CustomerCreditUnavailableException("Customer service did not confirm the release of "
                + reserveKey(loanId, generation));
        };
    }

    @Override
    public void markReservationUsed(LoanId loanId) {
        if (!generations.markUsed(loanId)) {
            throw new CustomerCreditUnavailableException("The credit reservation of loan " + loanId.getValue()
                + " was released meanwhile; the disbursement is not stored");
        }
    }

    /**
     * The compensation of {@code generation} is recorded as pending; sends it
     * and clears it once it is done. Under the customer's release by
     * reference (provider contract pending):
     * <ul>
     *   <li>422 RESERVATION_NOT_FOUND: nothing was reserved under the loan's
     *       reference, so there is nothing to release; the intent is done,
     *       logged and counted (loan_credit_releases_unmatched_total);</li>
     *   <li>422 RELEASE_EXCEEDS_RESERVATION: this service asked to release
     *       more than is held, a bug signal. The compensation stays pending
     *       with the code recorded, is never re-sent, and waits for an operator
     *       (loan_credit_reservations_operator{reason="release_exceeds_reservation"}).
     *       From the moment the code is recorded every caller (a disbursement
     *       of the loan included) gets {@link CreditReservationNeedsOperatorException},
     *       non-retryable (409 CREDIT_RESERVATION_HELD_FOR_OPERATOR), never
     *       the retryable "customer service unavailable". If the code could
     *       not be recorded the refusal stays retryable: the release is re-sent
     *       and refused again.</li>
     * </ul>
     */
    private Compensation sendCompensation(LoanId loanId, CustomerId customerId, Money amount, int generation) {
        Optional<String> refused = generations.releaseRefusedReason(loanId);
        if (refused.isPresent()) {
            throw new CreditReservationNeedsOperatorException(refused.get(), "Release "
                + compensationKey(loanId, generation) + " was refused with " + refused.get()
                + " and is left for an operator; not re-sent");
        }
        Compensation outcome;
        try {
            outcome = moveCredit(loanId, customerId, amount, paths.release(), compensationKey(loanId, generation))
                == CreditDecision.ACCEPTED ? Compensation.RELEASED : Compensation.REFUSED;
        } catch (ReleaseRefusedException refusal) {
            if (!RESERVATION_NOT_FOUND.equals(refusal.code())) {
                try {
                    generations.releaseRefused(loanId, generation, refusal.code());
                } catch (RuntimeException notRecorded) {
                    refusal.addSuppressed(notRecorded);
                    log.error("Customer service refused release {} with {}, and the refusal could not be recorded; "
                        + "it is re-sent", compensationKey(loanId, generation), refusal.code());
                    throw refusal;
                }
                log.error("Customer service refused release {} with {}: more than the loan's reservation; "
                    + "left for an operator", compensationKey(loanId, generation), refusal.code());
                throw new CreditReservationNeedsOperatorException(refusal.code(), "Release "
                    + compensationKey(loanId, generation) + " was refused with " + refusal.code()
                    + " and is left for an operator", refusal);
            }
            unmatchedReleases.increment();
            log.warn("Customer service holds no reservation for release {} ({}); nothing to release",
                compensationKey(loanId, generation), refusal.code());
            outcome = Compensation.NOTHING_HELD;
        }
        if (outcome != Compensation.REFUSED) {
            try {
                generations.compensationDone(loanId, generation);
            } catch (RuntimeException notRecorded) {
                log.warn("Release {} was answered but not recorded; it is re-sent under the same key",
                    compensationKey(loanId, generation), notRecorded);
            }
        }
        return outcome;
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
                    log.debug("Credit {} {} accepted; {} available", movement, idempotencyKey,
                        position.availableCredit());
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
                        + " for credit " + movement, error);
                }
                if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "INSUFFICIENT_CREDIT".equals(code)) {
                    log.info("Customer service refused credit {} {}: {}", movement, idempotencyKey, code);
                    return CreditDecision.REFUSED;
                }
                // Customer #13 (435aa83, still so at 8794365, copied in src/test/resources/contracts/customer-context.yaml):
                // 422 CURRENCY_MISMATCH when the ISO currency is valid but not the customer's credit
                // currency, 400 when it is malformed. Refresh the copied contract when customer's
                // push lands in the catalog.
                if (status == HttpStatus.UNPROCESSABLE_ENTITY.value()
                        && (RESERVATION_NOT_FOUND.equals(code) || RELEASE_EXCEEDS_RESERVATION.equals(code))) {
                    throw new ReleaseRefusedException(code, "Customer service answered " + status + " " + code
                        + " to credit " + movement, error);
                }
                if (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && "CURRENCY_MISMATCH".equals(code)) {
                    throw new CreditCurrencyMismatchException(amount.getCurrency(), null);
                }
                if (status == HttpStatus.BAD_REQUEST.value()) {
                    throw new CreditMovementRejectedException("Customer service answered " + status + " " + code
                        + " to credit " + movement + "; not retried", error);
                }
                if (status == HttpStatus.NOT_FOUND.value()) {
                    throw new CreditCustomerNotFoundException(customerId.getValue());
                }
                throw new CustomerCreditUnavailableException("Customer service answered " + status + " " + code
                    + " to credit " + movement, error);
            } catch (RestClientException unavailable) {
                throw new CustomerCreditUnavailableException("Customer service unavailable for credit " + movement,
                    unavailable);
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
