package com.bank.loan.infrastructure.web;

import com.bank.loan.infrastructure.config.SecurityConfiguration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.Set;

/**
 * Who may touch which loan. Staff and service callers (BANKER, LOAN_OFFICER,
 * ADMIN, SERVICE) act on any loan. Any other caller (CUSTOMER) acts only on
 * loans whose customer id equals the {@code customer_id} claim of their access
 * token (platform contract, "End-user and caller claims"), which
 * SecurityConfiguration makes the authentication name; a customer token
 * without the claim is refused (its name would be the Keycloak subject). Refusals are 403, also for a loan of another
 * customer, so ownership is checked after the loan is found.
 */
final class LoanAccessPolicy {

    private static final Set<String> UNRESTRICTED = Set.of("ROLE_BANKER", "ROLE_LOAN_OFFICER", "ROLE_ADMIN", "ROLE_SERVICE");

    private LoanAccessPolicy() {
    }

    static boolean isUnrestricted(Authentication caller) {
        return caller.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(UNRESTRICTED::contains);
    }

    /** Throws 403 unless the caller may act for this customer. */
    static void requireActsFor(Authentication caller, String customerId) {
        if (isUnrestricted(caller)) {
            return;
        }
        if (customerIdClaim(caller).isEmpty()) {
            throw new AccessDeniedException("Customer token without the customer_id claim");
        }
        if (!caller.getName().equals(customerId)) {
            throw new AccessDeniedException("Caller may not act for this customer");
        }
    }

    /**
     * Scope of the caller's idempotency keys: the customer id for customers,
     * the token subject (staff user or service account) otherwise.
     */
    static String idempotencyScope(Authentication caller) {
        return caller.getName();
    }

    static Optional<String> customerIdClaim(Authentication caller) {
        if (caller instanceof JwtAuthenticationToken jwt) {
            String value = jwt.getToken().getClaimAsString(SecurityConfiguration.CUSTOMER_ID_CLAIM);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }
}
