package com.bank.loan.infrastructure.config;

import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.infrastructure.external.AccountsLedgerHttpAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires the accounts-ledger HTTP adapter (journal.adapter=http, the default).
 * Calls carry this service's own client-credentials token (registration
 * journal.client-registration-id; the ledger authorizes SERVICE callers by
 * their azp and expects its id in aud), never the end user's token. Short
 * timeouts make a slow ledger fail fast. The manager is built here rather
 * than shared with the customer-service client so that either adapter can be
 * http while the other is in-memory; both cache tokens in the same
 * {@link OAuth2AuthorizedClientService}.
 */
@Configuration
@ConditionalOnProperty(name = "journal.adapter", havingValue = "http", matchIfMissing = true)
public class JournalClientConfiguration {

    @Bean
    JournalEntryPort journalEntryPort(
            RestClient.Builder builder,
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientService authorizedClients,
            @Value("${journal.base-url}") String baseUrl,
            @Value("${journal.client-registration-id:accounts-ledger}") String registrationId,
            @Value("${journal.path:/api/v1/journal-entries}") String path,
            @Value("${journal.max-attempts:3}") int maxAttempts,
            @Value("${journal.retry-backoff:PT0.2S}") Duration retryBackoff,
            @Value("${journal.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${journal.read-timeout:PT2S}") Duration readTimeout) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials()
            .build());
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        RestClient client = builder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
        return new AccountsLedgerHttpAdapter(client,
            CustomerCreditClientConfiguration.serviceToken(manager, registrationId), maxAttempts, path, retryBackoff);
    }
}
