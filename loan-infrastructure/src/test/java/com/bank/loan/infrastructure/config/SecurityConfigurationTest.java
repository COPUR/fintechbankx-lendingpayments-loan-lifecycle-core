package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    @Test
    void keycloakRealmRolesBecomeSpringRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("customer", "loan_officer"))));

        assertThat(SecurityConfiguration.realmRoles(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_CUSTOMER", "ROLE_LOAN_OFFICER");
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(jwt).getName()).isEqualTo("user-1");
    }

    @Test
    void tokensWithoutRealmRolesGetNoAuthorities() {
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("scope", "loan:read")))).isEmpty();
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("realm_access", Map.of())))).isEmpty();
    }

    @Test
    void tokenMustNameThisServiceInItsAudience() {
        var validator = SecurityConfiguration.tokenValidator("https://id.example/realms/fintechbankx", "svc-ln-loan-lifecycle");

        Jwt forUs = jwt(Map.of("iss", "https://id.example/realms/fintechbankx",
            "aud", List.of("svc-cus-profile-kyc", "svc-ln-loan-lifecycle")));
        Jwt forSomeoneElse = jwt(Map.of("iss", "https://id.example/realms/fintechbankx", "aud", List.of("svc-pay-initiation-settlement")));
        Jwt noAudience = jwt(Map.of("iss", "https://id.example/realms/fintechbankx"));
        Jwt wrongIssuer = jwt(Map.of("iss", "https://evil.example/realms/fintechbankx", "aud", List.of("svc-ln-loan-lifecycle")));

        assertThat(validator.validate(forUs).hasErrors()).isFalse();
        assertThat(validator.validate(forSomeoneElse).getErrors())
            .anySatisfy(error -> assertThat(error.getDescription()).contains("not intended for svc-ln-loan-lifecycle"));
        assertThat(validator.validate(noAudience).hasErrors()).isTrue();
        assertThat(validator.validate(wrongIssuer).hasErrors()).isTrue();
    }

    @Test
    void anEmptyAudienceSettingFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecurityConfiguration.audienceValidator(" "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OIDC_AUDIENCE");
    }

    @Test
    void decoderIsBuiltWithTheAudienceCheck() {
        assertThat(new SecurityConfiguration().jwtDecoder("http://localhost/certs", "http://localhost/realms/x", "svc-ln-loan-lifecycle"))
            .isNotNull();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void principalNameIsTheCustomerIdClaimWhenPresentElseTheSubject() {
        org.springframework.security.oauth2.jwt.Jwt customer = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "RS256").subject("5f0c2b7e-8d1a-4c3e-9b6f-2a7d4e1c9b30").claim("customer_id", "CUST-12345678").build();
        org.springframework.security.oauth2.jwt.Jwt staff = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "RS256").subject("8a1d3c55-0b6e-4f7a-a2c9-1e4b7d9f6a20").build();

        assertThat(SecurityConfiguration.principalName(customer)).isEqualTo("CUST-12345678");
        assertThat(SecurityConfiguration.principalName(staff)).isEqualTo("8a1d3c55-0b6e-4f7a-a2c9-1e4b7d9f6a20");
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(customer).getName()).isEqualTo("CUST-12345678");
    }
}
