package com.bank.loan.infrastructure.config;

import com.bank.loan.application.CustomerCreditService;
import com.bank.loan.infrastructure.external.CustomerProfileHttpAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

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
    CustomerCreditService customerCreditService(
            RestClient.Builder builder,
            OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
            @Value("${loan.customer-credit.base-url}") String baseUrl,
            @Value("${loan.customer-credit.client-registration-id:customer-service}") String registrationId,
            @Value("${loan.customer-credit.ledger-currency:AED}") String ledgerCurrency,
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
            Currency.getInstance(ledgerCurrency));
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
}
