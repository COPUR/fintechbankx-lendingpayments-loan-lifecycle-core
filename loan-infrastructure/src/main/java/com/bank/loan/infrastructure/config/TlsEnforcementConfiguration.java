package com.bank.loan.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link TlsEnforcement} as an early bean (a static
 * BeanFactoryPostProcessor), so the TLS assertion runs before any
 * datasource or Kafka bean is created. Picked up by the service's component
 * scan only: the Helm migration Job's context (LoanLifecycleApplication
 * "migrate") imports the DataSource and Flyway auto-configurations alone and
 * has no Kafka client; its DB_URL is the same chart value the chart already
 * refuses without sslmode=verify-full.
 */
@Configuration(proxyBeanMethods = false)
public class TlsEnforcementConfiguration {

    @Bean
    static TlsEnforcement tlsEnforcement(Environment environment) {
        return new TlsEnforcement(environment);
    }
}
