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
 * CREDIT_RESERVATION_SWEEP_ENABLED, _INTERVAL and _GRACE, so an operator can
 * switch the sweep off or retune it without a new image.
 */
class CreditReservationSweepConfigurationTest {

    @Test
    void defaultsMatchTheSweepDesign() throws Exception {
        Binder env = binder(Map.of());

        assertThat(env.bind("loan.credit-reservation.sweep.enabled", Boolean.class).get()).isTrue();
        assertThat(env.bind("loan.credit-reservation.sweep.interval", Duration.class).get()).isEqualTo(Duration.ofMinutes(1));
        assertThat(env.bind("loan.credit-reservation.sweep.grace", Duration.class).get()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theChartOverridesEnabledIntervalAndGrace() throws Exception {
        Binder env = binder(Map.of(
            "CREDIT_RESERVATION_SWEEP_ENABLED", "false",
            "CREDIT_RESERVATION_SWEEP_INTERVAL", "PT5M",
            "CREDIT_RESERVATION_SWEEP_GRACE", "PT30M"));

        assertThat(env.bind("loan.credit-reservation.sweep.enabled", Boolean.class).get()).isFalse();
        assertThat(env.bind("loan.credit-reservation.sweep.interval", Duration.class).get()).isEqualTo(Duration.ofMinutes(5));
        assertThat(env.bind("loan.credit-reservation.sweep.grace", Duration.class).get()).isEqualTo(Duration.ofMinutes(30));
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
