package com.bank.loan.infrastructure.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless OAuth2 resource server. Tokens come from the platform Keycloak
 * realm; realm roles become ROLE_* authorities for the @PreAuthorize rules
 * on LoanController. A token is accepted only if it was issued by the realm
 * AND names this service in {@code aud} (Keycloak audience mapper on every
 * calling client; platform contract addendum 2026-10-08). DPoP is not
 * required for lending (first-party and service clients). Actuator endpoints
 * are served on the management port, reachable only from the observability
 * namespace (NetworkPolicy).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    public static final String CUSTOMER_ID_CLAIM = "customer_id";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Spring forwards errors it resolves with sendError (unsupported media type, method not
                // allowed, uncaught exceptions) to /error; without this the caller would see 403 instead.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${loan.security.audience:svc-ln-loan-lifecycle}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audience));
    }

    /** {@code aud} must contain this service's id. */
    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("loan.security.audience (OIDC_AUDIENCE) must be set");
        }
        OAuth2Error wrongAudience = new OAuth2Error("invalid_token",
            "The token is not intended for " + audience, null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(wrongAudience);
    }

    static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> new JwtAuthenticationToken(jwt, realmRoles(jwt), principalName(jwt));
    }

    /**
     * The end user's customer profile id (claim customer_id, platform contract
     * "End-user and caller claims") when the token has one, so
     * authentication.getName() is the customer id for customers; the subject
     * (a Keycloak user or service-account UUID) for staff and services.
     */
    public static String principalName(Jwt jwt) {
        String customerId = jwt.getClaimAsString(CUSTOMER_ID_CLAIM);
        return customerId == null || customerId.isBlank() ? jwt.getSubject() : customerId;
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
            .map(String::valueOf)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            .toList();
    }
}
