package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerCreditClientConfigurationTest {

    private static final ClientRegistration REGISTRATION = ClientRegistration.withRegistrationId("customer-service")
        .clientId("svc-ln-loan-lifecycle")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .tokenUri("http://idp/token")
        .build();

    @Test
    void serviceTokenIsTheClientCredentialsTokenForThisServiceNotTheCallers() {
        AtomicReference<OAuth2AuthorizeRequest> seen = new AtomicReference<>();
        OAuth2AuthorizedClientManager manager = request -> {
            seen.set(request);
            OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "svc-token", Instant.now(), Instant.now().plusSeconds(300));
            return new OAuth2AuthorizedClient(REGISTRATION, request.getPrincipal().getName(), token);
        };

        Supplier<String> token = CustomerCreditClientConfiguration.serviceToken(manager, "customer-service");

        assertThat(token.get()).isEqualTo("svc-token");
        assertThat(seen.get().getClientRegistrationId()).isEqualTo("customer-service");
        assertThat(seen.get().getPrincipal().getName()).isEqualTo("svc-ln-loan-lifecycle");
    }

    @Test
    void missingServiceTokenFailsTheCallInsteadOfSendingItUnauthenticated() {
        Supplier<String> token = CustomerCreditClientConfiguration.serviceToken(request -> null, "customer-service");

        assertThatThrownBy(token::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("customer-service");
    }
}
