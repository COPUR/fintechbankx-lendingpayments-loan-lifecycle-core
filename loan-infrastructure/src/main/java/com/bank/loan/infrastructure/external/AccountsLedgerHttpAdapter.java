package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.JournalEntry;
import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti-corruption adapter to the accounts ledger (svc-acc-accounts-ledger,
 * accounts-ledger.yaml, ADR-030): POST /api/v1/journal-entries books one
 * double entry. The body's {@code reference} is the ledger's idempotency key
 * (no header): the same reference with the same lines answers 200 with the
 * first booking ({@code replay: true}), with other lines 409
 * IDEMPOTENCY_KEY_REUSED.
 *
 * <ul>
 *   <li>201 and 200 with a booking id are a {@link JournalEntryReceipt};
 *       any 2xx without one is {@link AccountsLedgerUnavailableException}
 *       (nothing proves the entry was booked).</li>
 *   <li>409 CONCURRENT_UPDATE / DUPLICATE_REQUEST are retried with the same
 *       reference, at most {@code maxAttempts} times, then unavailable.</li>
 *   <li>422 INSUFFICIENT_FUNDS / ACCOUNT_NOT_OPERATIONAL / UNBALANCED_ENTRY,
 *       409 IDEMPOTENCY_KEY_REUSED, 400 and 404 are
 *       {@link JournalEntryRejectedException} (non-retryable); anything else
 *       (401, 403, other 409 or 422, any 5xx, a transport error, no service
 *       token) is {@link AccountsLedgerUnavailableException}.</li>
 * </ul>
 *
 * Exceptions and logs carry the status and the ledger's code only: never the
 * ledger's message text (it may describe balances), the accounts, the
 * amounts or the reference.
 */
public class AccountsLedgerHttpAdapter implements JournalEntryPort {

    private static final Logger log = LoggerFactory.getLogger(AccountsLedgerHttpAdapter.class);
    static final String INTERACTION_ID_HEADER = "x-fapi-interaction-id";
    static final String DEFAULT_PATH = "/api/v1/journal-entries";
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Set<String> REFUSALS = Set.of("INSUFFICIENT_FUNDS", "ACCOUNT_NOT_OPERATIONAL", "UNBALANCED_ENTRY");

    private final RestClient restClient;
    private final Supplier<String> bearerToken;
    private final int maxAttempts;
    private final String path;
    private final Duration retryBackoff;

    public AccountsLedgerHttpAdapter(RestClient restClient, Supplier<String> bearerToken, int maxAttempts) {
        this(restClient, bearerToken, maxAttempts, DEFAULT_PATH, Duration.ZERO);
    }

    public AccountsLedgerHttpAdapter(RestClient restClient, Supplier<String> bearerToken, int maxAttempts, String path) {
        this(restClient, bearerToken, maxAttempts, path, Duration.ZERO);
    }

    /** The wait before a retry doubles with each attempt: retryBackoff, 2 x retryBackoff, ... */
    public AccountsLedgerHttpAdapter(RestClient restClient, Supplier<String> bearerToken, int maxAttempts, String path,
                                     Duration retryBackoff) {
        this.restClient = restClient;
        this.bearerToken = bearerToken;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.path = path;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public JournalEntryReceipt post(JournalEntry entry) {
        JournalEntryRequest request = toRequest(entry);
        for (int attempt = 1; ; attempt++) {
            try {
                ResponseEntity<JournalEntryResponse> response = restClient.post()
                    .uri(path)
                    .headers(this::addCallerHeaders)
                    .body(request)
                    .retrieve()
                    .toEntity(JournalEntryResponse.class);
                return receipt(response, request.reference());
            } catch (HttpClientErrorException error) {
                int status = error.getStatusCode().value();
                String code = errorCode(error);
                if (status == HttpStatus.CONFLICT.value() && isRetryable(code)) {
                    if (attempt < maxAttempts) {
                        log.info("Accounts ledger reported {} on a journal entry; retrying with the same reference", code);
                        pause(attempt);
                        continue;
                    }
                    throw new AccountsLedgerUnavailableException("Accounts ledger kept reporting " + code
                        + " for the journal entry");
                }
                if (isRejection(status, code)) {
                    log.info("Accounts ledger refused a journal entry: {} {}", status, code);
                    throw new JournalEntryRejectedException(code, "Accounts ledger answered " + status + " " + code
                        + " to the journal entry; not retried");
                }
                throw new AccountsLedgerUnavailableException("Accounts ledger answered " + status + " " + code
                    + " to the journal entry");
            } catch (RestClientResponseException serverError) {
                throw new AccountsLedgerUnavailableException("Accounts ledger answered " + serverError.getStatusCode().value()
                    + " to the journal entry");
            } catch (RestClientException unavailable) {
                throw new AccountsLedgerUnavailableException("Accounts ledger unavailable for the journal entry", unavailable);
            }
        }
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(retryBackoff.multipliedBy(1L << (attempt - 1)).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AccountsLedgerUnavailableException("Accounts ledger retry interrupted", interrupted);
        }
    }

    /** Nothing is trusted unless the booking names the reference that was sent. */
    private static JournalEntryReceipt receipt(ResponseEntity<JournalEntryResponse> response, String reference) {
        JournalEntryResponse body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null || body.journalEntryId() == null
                || body.journalEntryId().isBlank() || body.bookedAt() == null) {
            throw new AccountsLedgerUnavailableException("Accounts ledger answered " + response.getStatusCode().value()
                + " with no usable booking for the journal entry");
        }
        if (!reference.equals(body.reference())) {
            throw new AccountsLedgerUnavailableException("Accounts ledger booked a journal entry for another reference");
        }
        boolean replay = response.getStatusCode().value() == HttpStatus.OK.value() || Boolean.TRUE.equals(body.replay());
        log.debug("Journal entry {} {}", body.journalEntryId(), replay ? "replayed" : "booked");
        return new JournalEntryReceipt(body.journalEntryId(), replay, body.bookedAt());
    }

    private static boolean isRetryable(String code) {
        return "CONCURRENT_UPDATE".equals(code) || "DUPLICATE_REQUEST".equals(code);
    }

    private static boolean isRejection(int status, String code) {
        return (status == HttpStatus.UNPROCESSABLE_ENTITY.value() && REFUSALS.contains(code))
            || (status == HttpStatus.CONFLICT.value() && "IDEMPOTENCY_KEY_REUSED".equals(code))
            || status == HttpStatus.BAD_REQUEST.value()
            || status == HttpStatus.NOT_FOUND.value();
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
            throw new AccountsLedgerUnavailableException("No service token for the accounts ledger", noToken);
        }
        if (token == null || token.isBlank()) {
            throw new AccountsLedgerUnavailableException("No service token for the accounts ledger");
        }
        headers.setBearerAuth(token);
        String interactionId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.set(INTERACTION_ID_HEADER, interactionId != null ? interactionId : UUID.randomUUID().toString());
    }

    private static JournalEntryRequest toRequest(JournalEntry entry) {
        return new JournalEntryRequest(entry.reference(), entry.currency().getCurrencyCode(), entry.narrative(),
            entry.lines().stream()
                .map(line -> new PostingLine(line.accountId(), line.side().name(), line.amount().getAmount()))
                .toList());
    }

    /** PostJournalEntryRequest; narrative is optional and left out when absent (the schema allows no null). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JournalEntryRequest(String reference, String currency, String narrative, List<PostingLine> lines) {
    }

    record PostingLine(String accountId, String side, BigDecimal amount) {
    }

    /** JournalEntryResponse: only what the receipt needs; any other field is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record JournalEntryResponse(String journalEntryId, String reference, Instant bookedAt, Boolean replay) {
    }
}
