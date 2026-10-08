package com.bank.loan.infrastructure.config;

import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.infrastructure.external.CreditReservationSweep;
import com.bank.loan.infrastructure.external.CustomerProfileHttpAdapter;
import com.bank.loan.infrastructure.external.PostgresAdvisoryLock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.bank.loan.infrastructure.external.JdbcReservationGenerations;
import com.bank.loan.infrastructure.external.ReservationGenerations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.function.Supplier;

/**
 * Wires the customer-profile-kyc HTTP adapter (the default). Calls carry this
 * service's own client-credentials token (the provider authorizes service
 * callers by their SERVICE realm role), never the end user's token, so credit
 * moves also work from the outbox relay and other non-request threads. Short
 * timeouts make a slow customer service fail fast instead of exhausting this
 * service's request threads.
 */
@Configuration
@ConditionalOnProperty(name = "loan.customer-credit.adapter", havingValue = "http", matchIfMissing = true)
public class CustomerCreditClientConfiguration {

    static final String SERVICE_PRINCIPAL = "svc-ln-loan-lifecycle";
    static final String OPERATOR_GAUGE = "loan.credit.reservations.operator";

    @Bean
    OAuth2AuthorizedClientManager serviceAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials()
            .build());
        return manager;
    }

    @Bean
    ReservationGenerations reservationGenerations(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcReservationGenerations(jdbc, transactionManager);
    }

    /**
     * Never-disbursed loans whose credit may still be held by the customer
     * service (loan_credit_reservations_pending): pending compensations and
     * outstanding reservations. Short-lived during a disbursement; alert when
     * it stays above zero for longer than the sweep's grace period.
     */
    @Bean
    Gauge creditReservationsPendingGauge(MeterRegistry registry, ReservationGenerations reservationGenerations) {
        return Gauge.builder("loan.credit.reservations.pending", reservationGenerations, ReservationGenerations::countPending)
            .description("Never-disbursed loans whose credit reservation is not yet released or confirmed released")
            .register(registry);
    }

    /**
     * Reservations left for an operator (loan_credit_reservations_operator):
     * reason="unconfirmed" counts reserves that were sent but never answered
     * and that the sweep will not release blind. Alert on any value above zero.
     */
    @Bean
    Gauge creditReservationsUnconfirmedGauge(MeterRegistry registry, ReservationGenerations reservationGenerations) {
        return Gauge.builder(OPERATOR_GAUGE, reservationGenerations, ReservationGenerations::countUnconfirmed)
            .tag("reason", "unconfirmed")
            .description("Credit reservations an operator must resolve")
            .register(registry);
    }

    @Bean
    CustomerCreditService customerCreditService(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
            ReservationGenerations reservationGenerations,
            @Value("${loan.customer-credit.base-url}") String baseUrl,
            @Value("${loan.customer-credit.client-registration-id:customer-service}") String registrationId,
            @Value("${loan.customer-credit.ledger-currency:}") String ledgerCurrency,
            @Value("${loan.customer-credit.max-attempts:3}") int maxAttempts,
            @Value("${loan.customer-credit.paths.credit-position:/api/v1/customers/{customerId}/credit}") String positionPath,
            @Value("${loan.customer-credit.paths.reserve:/api/v1/customers/{customerId}/credit/reserve}") String reservePath,
            @Value("${loan.customer-credit.paths.release:/api/v1/customers/{customerId}/credit/release}") String releasePath,
            @Value("${loan.customer-credit.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${loan.customer-credit.read-timeout:PT2S}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        RestClient client = builder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
        return new CustomerProfileHttpAdapter(client,
            serviceToken(serviceAuthorizedClientManager, registrationId),
            ledgerCurrency(ledgerCurrency), reservationGenerations, maxAttempts,
            new CustomerProfileHttpAdapter.Paths(positionPath, reservePath, releasePath));
    }

    /**
     * The currency the customer service holds credit in when its response
     * does not say. Deliberately no default: it must be configured
     * (CUSTOMER_CREDIT_LEDGER_CURRENCY), never assumed.
     */
    public static Currency ledgerCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException(
                "loan.customer-credit.ledger-currency (CUSTOMER_CREDIT_LEDGER_CURRENCY) must be set to an ISO 4217 code");
        }
        return Currency.getInstance(code.trim());
    }

    /**
     * Client-credentials token for this service. The manager caches the token
     * and fetches a new one only when it is about to expire.
     */
    static Supplier<String> serviceToken(OAuth2AuthorizedClientManager manager, String registrationId) {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
            .principal(SERVICE_PRINCIPAL)
            .build();
        return () -> {
            OAuth2AuthorizedClient client = manager.authorize(request);
            if (client == null) {
                throw new IllegalStateException("No service token for client registration " + registrationId);
            }
            return client.getAccessToken().getTokenValue();
        };
    }

    /**
     * Review 5460235552: the recovery sweep. On by default
     * (CREDIT_RESERVATION_SWEEP_ENABLED); runs at start-up and then
     * loan.credit-reservation.sweep.interval after the previous run ended.
     */
    @Configuration
    @EnableScheduling
    // Repeated here: component scanning registers this nested class on its own.
    @ConditionalOnProperty(name = "loan.customer-credit.adapter", havingValue = "http", matchIfMissing = true)
    static class SweepConfiguration {

        @Bean
        @ConditionalOnProperty(name = "loan.credit-reservation.sweep.enabled", havingValue = "true", matchIfMissing = true)
        CreditReservationSweep creditReservationSweep(ReservationGenerations reservationGenerations,
                                                      CustomerCreditService customerCreditService,
                                                      JdbcTemplate jdbc, Clock clock,
                                                      @Value("${loan.credit-reservation.sweep.grace:PT10M}") Duration grace,
                                                      @Value("${loan.credit-reservation.sweep.batch-size:50}") int batchSize) {
            return new CreditReservationSweep(reservationGenerations, customerCreditService,
                new PostgresAdvisoryLock(jdbc, CreditReservationSweep.SWEEP_LOCK_KEY), clock, grace, batchSize);
        }

        @Bean
        @ConditionalOnProperty(name = "loan.credit-reservation.sweep.enabled", havingValue = "true", matchIfMissing = true)
        SweepSchedule creditReservationSweepSchedule(CreditReservationSweep sweep) {
            return new SweepSchedule(sweep);
        }
    }

    static class SweepSchedule {
        private final CreditReservationSweep sweep;

        SweepSchedule(CreditReservationSweep sweep) {
            this.sweep = sweep;
        }

        @Scheduled(initialDelayString = "${loan.credit-reservation.sweep.initial-delay:PT0S}",
                   fixedDelayString = "${loan.credit-reservation.sweep.interval:PT1M}")
        void sweep() {
            sweep.sweepOnce();
        }
    }
}
