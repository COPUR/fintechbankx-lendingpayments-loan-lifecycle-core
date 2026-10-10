package com.bank.loan.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Set;

/**
 * Round 5 common item 2 (governance answer 2b): the service refuses to start
 * unless its connections are encrypted and verified. Runs as a
 * {@link BeanFactoryPostProcessor}, before any singleton exists, so no
 * connection is attempted first:
 * <ul>
 *   <li>{@code spring.datasource.url} (DB_URL) must carry {@code sslmode=verify-full};</li>
 *   <li>when a Kafka client is configured (a {@link KafkaTemplate},
 *       {@link ProducerFactory} or {@link ConsumerFactory} bean definition
 *       exists), the effective {@code security.protocol} must be one of the
 *       TLS protocols: SASL_SSL (MSK IAM, profile kafka-msk) or SSL (Strimzi
 *       mutual TLS with the KafkaUser certificate, profile kafka-strimzi);
 *       PLAINTEXT, SASL_PLAINTEXT and unset are refused. This is the set the
 *       payments, mandates, bulk and request-to-pay services accept. The
 *       effective value is {@code spring.kafka.properties.security.protocol}
 *       if set, else {@code spring.kafka.security.protocol}
 *       (KAFKA_SECURITY_PROTOCOL), else Kafka's default PLAINTEXT.</li>
 * </ul>
 * {@code fintechbankx.tls.enforce} is true in application.yml. Only explicit
 * local and test configuration sets it false (profile {@code local}, the
 * bootstrap module's test resources); the chart never does and refuses the
 * env key and the local profile. The failure names the offending setting,
 * never the datasource URL, which may carry a credential.
 */
public final class TlsEnforcement implements BeanFactoryPostProcessor {

    public static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String KAFKA_PROTOCOL = "spring.kafka.security.protocol";
    static final String KAFKA_PROTOCOL_PROPERTY = "spring.kafka.properties.security.protocol";
    static final String SASL_SSL = "SASL_SSL";
    static final String SSL = "SSL";
    /** The Kafka security protocols that encrypt and verify the connection; anything else stops the start. */
    static final Set<String> TLS_PROTOCOLS = Set.of(SASL_SSL, SSL);
    private static final String KAFKA_DEFAULT_PROTOCOL = "PLAINTEXT";
    private static final String HOW_TO_RELAX = " Only local runs and tests set " + ENFORCE
        + "=false (profile local, test resources); the chart never does.";
    private static final Logger log = LoggerFactory.getLogger(TlsEnforcement.class);

    private final Environment environment;

    public TlsEnforcement(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!environment.getProperty(ENFORCE, Boolean.class, true)) {
            log.warn("{} is false: the datasource and Kafka TLS assertions are off (local runs and tests only)", ENFORCE);
            return;
        }
        if (!verifiesServerCertificate(environment.getProperty(DATASOURCE_URL))) {
            throw new IllegalStateException(ENFORCE + " is true but " + DATASOURCE_URL
                + " (DB_URL) does not use sslmode=verify-full: the database connection must verify the server"
                + " certificate against the mounted CA bundle." + HOW_TO_RELAX);
        }
        if (hasKafkaClient(beanFactory)) {
            String protocol = kafkaSecurityProtocol();
            if (!TLS_PROTOCOLS.contains(protocol)) {
                throw new IllegalStateException(ENFORCE + " is true but " + KAFKA_PROTOCOL
                    + " (KAFKA_SECURITY_PROTOCOL) is '" + protocol + "', not " + SASL_SSL + " or " + SSL
                    + " (Strimzi mutual TLS), while a Kafka client is configured." + HOW_TO_RELAX);
            }
            log.info("TLS assertion passed: datasource sslmode=verify-full, Kafka security.protocol {}", protocol);
            return;
        }
        log.info("TLS assertion passed: datasource sslmode=verify-full; no Kafka client configured");
    }

    /** True when the JDBC URL's query string carries sslmode=verify-full (the PostgreSQL driver's parameter). */
    static boolean verifiesServerCertificate(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return false;
        }
        int query = jdbcUrl.indexOf('?');
        if (query < 0) {
            return false;
        }
        for (String pair : jdbcUrl.substring(query + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && "sslmode".equals(pair.substring(0, separator).trim())
                    && "verify-full".equals(pair.substring(separator + 1).trim())) {
                return true;
            }
        }
        return false;
    }

    private String kafkaSecurityProtocol() {
        String fromProperties = environment.getProperty(KAFKA_PROTOCOL_PROPERTY);
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties.trim();
        }
        String protocol = environment.getProperty(KAFKA_PROTOCOL);
        return protocol == null || protocol.isBlank() ? KAFKA_DEFAULT_PROTOCOL : protocol.trim();
    }

    /** A Kafka client is configured when the context defines a template, producer factory or consumer factory. */
    static boolean hasKafkaClient(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(KafkaTemplate.class, true, false).length > 0
            || beanFactory.getBeanNamesForType(ProducerFactory.class, true, false).length > 0
            || beanFactory.getBeanNamesForType(ConsumerFactory.class, true, false).length > 0;
    }
}
