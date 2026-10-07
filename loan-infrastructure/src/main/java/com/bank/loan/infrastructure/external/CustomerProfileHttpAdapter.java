package com.bank.loan.infrastructure.external;

import com.bank.loan.application.CustomerCreditService;
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

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Anti-corruption adapter to the customer-profile-kyc service, which owns
 * credit limits and reservations (svc-cus-profile-kyc, customer-context.yaml).
 *
 * Reads only the credit figures from CustomerResponse; the name, e-mail and
 * phone fields the provider returns are ignored and never stored here.
 *
 * The provider reports availableCredit as a bare number, so it is read in
 * the configured ledger currency and compared only with amounts in that
 * currency.
 */
public class CustomerProfileHttpAdapter implements CustomerCreditService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileHttpAdapter.class);
    static final String INTERACTION_ID_HEADER = "x-fapi-interaction-id";
    static final String IDEMPOTENCY_KEY_HEADER = "x-idempotency-key";

    private final RestClient restClient;
    private final Supplier<String> bearerToken;
    private final Currency ledgerCurrency;

    public CustomerProfileHttpAdapter(RestClient restClient, Supplier<String> bearerToken, Currency ledgerCurrency) {
        this.restClient = restClient;
        this.bearerToken = bearerToken;
        this.ledgerCurrency = ledgerCurrency;
    }

    @Override
    public boolean hasAvailableCredit(CustomerId customerId, Money amount) {
        if (!ledgerCurrency.equals(amount.getCurrency())) {
            log.warn("Credit check in {} refused: customer credit is held in {}", amount.getCurrency(), ledgerCurrency);
            return false;
        }
        return getAvailableCredit(customerId).compareTo(amount) >= 0;
    }

    @Override
    public boolean reserveCredit(CustomerId customerId, Money amount) {
        return moveCredit(customerId, amount, "reserve");
    }

    @Override
    public boolean releaseCredit(CustomerId customerId, Money amount) {
        return moveCredit(customerId, amount, "release");
    }

    @Override
    public Money getAvailableCredit(CustomerId customerId) {
        try {
            CustomerCreditView view = restClient.get()
                .uri("/api/v1/customers/{customerId}", customerId.getValue())
                .headers(this::addCallerHeaders)
                .retrieve()
                .body(CustomerCreditView.class);
            if (view == null || view.availableCredit() == null) {
                return Money.zero(ledgerCurrency);
            }
            return Money.of(view.availableCredit(), ledgerCurrency);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Money.zero(ledgerCurrency);
        }
    }

    private boolean moveCredit(CustomerId customerId, Money amount, String action) {
        try {
            restClient.post()
                .uri("/api/v1/customers/{customerId}/credit/{action}", customerId.getValue(), action)
                .headers(this::addCallerHeaders)
                .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .body(new CreditMovementRequest(amount.getAmount(), amount.getCurrency().getCurrencyCode()))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() == HttpStatus.NOT_FOUND
                || error.getStatusCode() == HttpStatus.CONFLICT
                || error.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                log.info("Customer service refused credit {} for {}: {}", action, customerId.getValue(), error.getStatusCode());
                return false;
            }
            throw error;
        }
    }

    private void addCallerHeaders(HttpHeaders headers) {
        String token = bearerToken.get();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        String interactionId = MDC.get(CorrelationIdFilter.MDC_KEY);
        headers.set(INTERACTION_ID_HEADER, interactionId != null ? interactionId : UUID.randomUUID().toString());
    }

    record CreditMovementRequest(BigDecimal amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomerCreditView(String customerId, BigDecimal availableCredit, String status) {
    }
}
