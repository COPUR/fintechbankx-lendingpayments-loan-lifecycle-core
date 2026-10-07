package com.bank.loan.infrastructure.config;

import com.bank.loan.application.CustomerCreditService;
import com.bank.loan.infrastructure.external.CustomerProfileHttpAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Currency;

/**
 * Wires the customer-profile-kyc HTTP adapter (the default). Calls carry the
 * caller's bearer token and short timeouts so a slow customer service fails
 * fast instead of exhausting this service's request threads.
 */
@Configuration
@ConditionalOnProperty(name = "loan.customer-credit.adapter", havingValue = "http", matchIfMissing = true)
public class CustomerCreditClientConfiguration {

    @Bean
    CustomerCreditService customerCreditService(
            RestClient.Builder builder,
            @Value("${loan.customer-credit.base-url}") String baseUrl,
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
        return new CustomerProfileHttpAdapter(client, CustomerCreditClientConfiguration::currentBearerToken,
            Currency.getInstance(ledgerCurrency));
    }

    private static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        return null;
    }
}
