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

    @Test
    void ledgerCurrencyMustBeConfiguredNeverAssumed() {
        assertThatThrownBy(() -> CustomerCreditClientConfiguration.ledgerCurrency(""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CUSTOMER_CREDIT_LEDGER_CURRENCY");
        assertThatThrownBy(() -> CustomerCreditClientConfiguration.ledgerCurrency(null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(CustomerCreditClientConfiguration.ledgerCurrency(" USD ").getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void oldestPendingAgeIsZeroWithoutBacklog() {
        var outbox = org.mockito.Mockito.mock(com.bank.loan.infrastructure.outbox.SpringDataOutboxRepository.class);
        java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), java.time.ZoneOffset.UTC);
        org.mockito.Mockito.when(outbox.oldestPendingOccurredAt())
            .thenReturn(java.util.Optional.empty())
            .thenReturn(java.util.Optional.of(Instant.parse("2026-10-08T05:58:30Z")));

        assertThat(OutboxConfiguration.oldestPendingAgeSeconds(outbox, clock)).isZero();
        assertThat(OutboxConfiguration.oldestPendingAgeSeconds(outbox, clock)).isEqualTo(90d);
    }

    @Test
    void theRecoverySweepIsOnByDefaultAndCanBeSwitchedOff() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(com.bank.loan.infrastructure.external.ReservationGenerations.class,
                    () -> org.mockito.Mockito.mock(com.bank.loan.infrastructure.external.ReservationGenerations.class))
                .withBean(com.bank.loan.domain.port.out.CustomerCreditService.class,
                    () -> org.mockito.Mockito.mock(com.bank.loan.domain.port.out.CustomerCreditService.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                    () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class)
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                    new org.springframework.boot.convert.ApplicationConversionService()))
                .withPropertyValues("loan.credit-reservation.sweep.initial-delay=PT1H")
                .withUserConfiguration(CustomerCreditClientConfiguration.SweepConfiguration.class);

        runner.run(context -> {
            assertThat(context).hasSingleBean(com.bank.loan.infrastructure.external.CreditReservationSweep.class);
            // CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED is off unless set: unanswered reserves wait for an operator
            assertThat(context.getBean(com.bank.loan.infrastructure.external.CreditReservationSweep.class).releasesUnanswered()).isFalse();
        });
        runner.withPropertyValues("loan.credit-reservation.sweep.release-unanswered=true").run(context ->
            assertThat(context.getBean(com.bank.loan.infrastructure.external.CreditReservationSweep.class).releasesUnanswered()).isTrue());
        runner.withPropertyValues("loan.credit-reservation.sweep.enabled=false").run(context -> assertThat(context)
            .doesNotHaveBean(com.bank.loan.infrastructure.external.CreditReservationSweep.class));
    }

    /**
     * loan_credit_reservations_operator{reason="unconfirmed"} counts the
     * reserves the sweep left for an operator. It exists only while
     * release-unanswered is off; once the sweep releases unanswered reserves
     * by reference nothing can be left UNCONFIRMED, and the series goes.
     */
    @Test
    void theUnconfirmedOperatorGaugeIsExportedOnlyWhileUnansweredReservesAreLeftForAnOperator() {
        com.bank.loan.infrastructure.external.ReservationGenerations generations =
            org.mockito.Mockito.mock(com.bank.loan.infrastructure.external.ReservationGenerations.class);
        org.mockito.Mockito.when(generations.countUnconfirmed()).thenReturn(3L);
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(com.bank.loan.infrastructure.external.ReservationGenerations.class, () -> generations)
                .withBean(com.bank.loan.domain.port.out.CustomerCreditService.class,
                    () -> org.mockito.Mockito.mock(com.bank.loan.domain.port.out.CustomerCreditService.class))
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                    () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class)
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                    new org.springframework.boot.convert.ApplicationConversionService()))
                .withPropertyValues("loan.credit-reservation.sweep.initial-delay=PT1H")
                .withUserConfiguration(CustomerCreditClientConfiguration.SweepConfiguration.class);

        runner.run(context -> assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
            .get("loan.credit.reservations.operator").tag("reason", "unconfirmed").gauge().value()).isEqualTo(3.0));
        // the gauge does not depend on the sweep itself being on
        runner.withPropertyValues("loan.credit-reservation.sweep.enabled=false").run(context ->
            assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .find("loan.credit.reservations.operator").tag("reason", "unconfirmed").gauge()).isNotNull());
        runner.withPropertyValues("loan.credit-reservation.sweep.release-unanswered=true").run(context ->
            assertThat(context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
                .find("loan.credit.reservations.operator").tag("reason", "unconfirmed").gauge()).isNull());
    }

    @Test
    void reservationGaugesCarryNoIdentifiers() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.bank.loan.infrastructure.external.ReservationGenerations generations =
            org.mockito.Mockito.mock(com.bank.loan.infrastructure.external.ReservationGenerations.class);
        org.mockito.Mockito.when(generations.countPending()).thenReturn(2L);
        CustomerCreditClientConfiguration configuration = new CustomerCreditClientConfiguration();

        configuration.creditReservationsPendingGauge(registry, generations);
        org.mockito.Mockito.when(generations.countReleaseRefused()).thenReturn(4L);
        configuration.creditReleasesRefusedGauge(registry, generations);
        org.mockito.Mockito.when(generations.countUnconfirmed()).thenReturn(1L);
        new CustomerCreditClientConfiguration.SweepConfiguration().creditReservationsUnconfirmedGauge(registry, generations);

        assertThat(registry.get("loan.credit.reservations.pending").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("loan.credit.reservations.pending").gauge().getId().getTags()).isEmpty();
        assertThat(registry.get("loan.credit.reservations.operator").tag("reason", "release_exceeds_reservation")
            .gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("loan.credit.reservations.operator").tag("reason", "unconfirmed")
            .gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("loan.credit.reservations.operator").gauges())
            .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactly("reason"));
    }

    /**
     * The sweep releases a reserve that was never answered once the grace
     * period has passed, so the grace must outlast the longest a reserve call
     * can still be in flight: max-attempts x (connect + read timeout).
     */
    @Test
    void theSweepGraceMustOutlastTheLongestReserveCall() {
        java.time.Duration connect = java.time.Duration.ofSeconds(1);
        java.time.Duration read = java.time.Duration.ofSeconds(2);

        assertThatThrownBy(() -> CustomerCreditClientConfiguration.checkGrace(java.time.Duration.ofSeconds(9), connect, read, 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CREDIT_RESERVATION_SWEEP_GRACE");
        assertThatThrownBy(() -> CustomerCreditClientConfiguration.checkGrace(java.time.Duration.ZERO, connect, read, 1))
            .isInstanceOf(IllegalStateException.class);
        CustomerCreditClientConfiguration.checkGrace(java.time.Duration.ofSeconds(10), connect, read, 3);
        CustomerCreditClientConfiguration.checkGrace(java.time.Duration.ofMinutes(10), connect, read, 3);

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(com.bank.loan.infrastructure.external.ReservationGenerations.class,
                () -> org.mockito.Mockito.mock(com.bank.loan.infrastructure.external.ReservationGenerations.class))
            .withBean(com.bank.loan.domain.port.out.CustomerCreditService.class,
                () -> org.mockito.Mockito.mock(com.bank.loan.domain.port.out.CustomerCreditService.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class,
                () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
            .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
            .withBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry.class)
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                new org.springframework.boot.convert.ApplicationConversionService()))
            .withPropertyValues("loan.credit-reservation.sweep.initial-delay=PT1H", "loan.credit-reservation.sweep.grace=PT5S")
            .withUserConfiguration(CustomerCreditClientConfiguration.SweepConfiguration.class)
            .run(context -> {
                assertThat(context).hasFailed();
                // for the grace, not for a missing bean
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("CREDIT_RESERVATION_SWEEP_GRACE");
            });
    }
}
