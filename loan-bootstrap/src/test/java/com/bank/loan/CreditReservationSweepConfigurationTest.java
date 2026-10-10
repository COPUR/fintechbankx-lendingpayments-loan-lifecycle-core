package com.bank.loan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit reservation recovery sweep (CreditReservationSweep) is driven by
 * loan.credit-reservation.sweep.*; the chart's ConfigMap sets
 * CREDIT_RESERVATION_SWEEP_ENABLED, _INTERVAL, _GRACE and
 * _RELEASE_UNANSWERED, so an operator can switch the sweep off, retune it, or
 * turn the release of unanswered reserves on without a new image.
 */
class CreditReservationSweepConfigurationTest {

    @Test
    void defaultsMatchTheSweepDesign() throws Exception {
        Binder env = binder(Map.of());

        assertThat(env.bind("loan.credit-reservation.sweep.enabled", Boolean.class).get()).isTrue();
        assertThat(env.bind("loan.credit-reservation.sweep.interval", Duration.class).get()).isEqualTo(Duration.ofMinutes(1));
        assertThat(env.bind("loan.credit-reservation.sweep.grace", Duration.class).get()).isEqualTo(Duration.ofMinutes(10));
        // Off until customer-profile-kyc-core #13 (a6ebe01 or later) is deployed: unanswered reserves wait for an operator.
        assertThat(env.bind("loan.credit-reservation.sweep.release-unanswered", Boolean.class).get()).isFalse();
    }

    @Test
    void theChartOverridesEnabledIntervalGraceAndReleaseUnanswered() throws Exception {
        Binder env = binder(Map.of(
            "CREDIT_RESERVATION_SWEEP_ENABLED", "false",
            "CREDIT_RESERVATION_SWEEP_INTERVAL", "PT5M",
            "CREDIT_RESERVATION_SWEEP_GRACE", "PT30M",
            "CREDIT_RESERVATION_SWEEP_RELEASE_UNANSWERED", "true"));

        assertThat(env.bind("loan.credit-reservation.sweep.enabled", Boolean.class).get()).isFalse();
        assertThat(env.bind("loan.credit-reservation.sweep.interval", Duration.class).get()).isEqualTo(Duration.ofMinutes(5));
        assertThat(env.bind("loan.credit-reservation.sweep.grace", Duration.class).get()).isEqualTo(Duration.ofMinutes(30));
        assertThat(env.bind("loan.credit-reservation.sweep.release-unanswered", Boolean.class).get()).isTrue();
    }

    private static Binder binder(Map<String, Object> podEnv) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod-env", podEnv));
        for (PropertySource<?> document : documents) {
            if (document.getProperty("spring.config.activate.on-profile") == null) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment);
    }
}
