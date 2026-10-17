package com.bank.loan.infrastructure.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Round 5 common item 2 (governance answer 2b) and round 6 (guardrail 4a, loan
 * #14 review): the service refuses to start unless its connections are
 * encrypted and verified. Runs as a {@link BeanFactoryPostProcessor}, before
 * any singleton exists, so no connection is attempted first. Settings are read
 * the way the driver and the client read them, not by substring:
 * <ul>
 *   <li>every datasource URL a pool can use, {@code spring.datasource.url}
 *       (DB_URL), {@code spring.datasource.hikari.jdbc-url} (Hikari's own URL,
 *       which replaces the first once the pool is bound) and
 *       {@code spring.flyway.url} (a second datasource), bound with relaxed
 *       names as Spring binds them, is parsed as PgJDBC parses it: the query
 *       after the first {@code ?}, pairs split on {@code &}, the key before the
 *       first {@code =}, keys case-sensitive. {@code sslmode} must appear
 *       exactly once, spelt in lower case (the driver ignores {@code SSLMODE}),
 *       and equal {@code verify-full}; {@code sslfactory}, {@code sslfactoryarg},
 *       {@code sslhostnameverifier}, {@code sslpasswordcallback} and
 *       {@code service} are refused in any case, since they can replace the
 *       certificate or host name check or load a service file;</li>
 *   <li>when a Kafka client is configured (a {@link KafkaTemplate},
 *       {@link ProducerFactory}, {@link ConsumerFactory} or
 *       {@link KafkaListenerContainerFactory} bean definition exists),
 *       {@code spring.kafka} is bound to {@link KafkaProperties} and the
 *       effective {@code security.protocol} of
 *       {@link KafkaProperties#buildProducerProperties} (and of
 *       {@link KafkaProperties#buildConsumerProperties} when a consumer factory
 *       exists) must be one of the TLS protocols: SASL_SSL (MSK IAM, profile
 *       kafka-msk) or SSL (Strimzi mutual TLS with the KafkaUser certificate,
 *       profile kafka-strimzi); PLAINTEXT, SASL_PLAINTEXT and unset are refused,
 *       wherever the client would take them from ({@code spring.kafka.security.protocol},
 *       {@code spring.kafka.properties.*}, {@code spring.kafka.producer.*},
 *       {@code spring.kafka.consumer.*}). This is the set the payments,
 *       mandates, bulk and request-to-pay services accept.</li>
 * </ul>
 * {@code fintechbankx.tls.enforce} is true in application.yml. Only explicit
 * local and test configuration sets it false (profile {@code local}, the
 * bootstrap module's test resources); the chart never does and refuses the
 * env key, every profile name and the JVM options that could carry them. The
 * failure names the offending setting and its sslmode or protocol, never the
 * datasource URL, which may carry a credential. The migration Job's context
 * imports {@link TlsEnforcementConfiguration} explicitly, so its DB_URL is
 * checked too (it has no Kafka client).
 */
public final class TlsEnforcement implements BeanFactoryPostProcessor {

    public static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String HIKARI_JDBC_URL = "spring.datasource.hikari.jdbc-url";
    static final String FLYWAY_URL = "spring.flyway.url";
    static final String KAFKA_PROTOCOL = "spring.kafka.security.protocol";
    static final String REQUIRED_SSLMODE = "verify-full";
    static final String SASL_SSL = "SASL_SSL";
    static final String SSL = "SSL";
    /** The Kafka security protocols that encrypt and verify the connection; anything else stops the start. */
    static final Set<String> TLS_PROTOCOLS = Set.of(SASL_SSL, SSL);
    /** PgJDBC parameters that replace certificate or host name verification, or load a connection service file. */
    static final Set<String> VERIFICATION_BYPASS_KEYS =
        Set.of("sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback", "service");
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
        Binder binder = Binder.get(environment);
        List<String> problems = new ArrayList<>();
        checkDatasourceUrl(DATASOURCE_URL + " (DB_URL)", binder.bind(DATASOURCE_URL, String.class).orElse(null), problems);
        String hikariUrl = binder.bind(HIKARI_JDBC_URL, String.class).orElse(null);
        if (hikariUrl != null && !hikariUrl.isBlank()) {
            checkDatasourceUrl(HIKARI_JDBC_URL + " (SPRING_DATASOURCE_HIKARI_JDBC_URL)", hikariUrl, problems);
        }
        String flywayUrl = binder.bind(FLYWAY_URL, String.class).orElse(null);
        if (flywayUrl != null && !flywayUrl.isBlank()) {
            checkDatasourceUrl(FLYWAY_URL + " (SPRING_FLYWAY_URL)", flywayUrl, problems);
        }
        String kafka = "no Kafka client configured";
        if (hasKafkaClient(beanFactory)) {
            KafkaProperties kafkaProperties = binder.bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
            String producer = checkKafkaProtocol("producer", kafkaProperties.buildProducerProperties(null), problems);
            kafka = "Kafka producer security.protocol " + producer;
            if (hasKafkaConsumer(beanFactory)) {
                String consumer = checkKafkaProtocol("consumer", kafkaProperties.buildConsumerProperties(null), problems);
                kafka += ", consumer security.protocol " + consumer;
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(ENFORCE + " is true but " + String.join("; ", problems) + "." + HOW_TO_RELAX);
        }
        log.info("TLS assertion passed: every datasource URL has sslmode=verify-full; {}", kafka);
    }

    private static void checkDatasourceUrl(String setting, String url, List<String> problems) {
        for (String problem : datasourceProblems(url)) {
            problems.add(setting + " " + problem
                + ": the database connection must verify the server certificate against the mounted CA bundle (sslmode="
                + REQUIRED_SSLMODE + ")");
        }
    }

    /**
     * What is wrong with a JDBC URL, read as PgJDBC reads it; empty when it
     * verifies the server certificate. The reasons name parameters and the
     * sslmode value only, never the URL.
     */
    static List<String> datasourceProblems(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return List.of("is not set");
        }
        List<String> problems = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        String mixedCaseSslmode = null;
        int query = jdbcUrl.indexOf('?');
        if (query >= 0) {
            for (String pair : jdbcUrl.substring(query + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                String lowerKey = key.toLowerCase(Locale.ROOT);
                if (VERIFICATION_BYPASS_KEYS.contains(lowerKey)) {
                    problems.add("sets " + lowerKey + ", which can bypass certificate or host name verification");
                }
                if ("sslmode".equals(key)) {
                    modes.add(value);
                } else if ("sslmode".equals(lowerKey)) {
                    mixedCaseSslmode = key;
                }
            }
        }
        if (modes.size() > 1) {
            problems.add("sets sslmode " + modes.size() + " times; exactly one sslmode=" + REQUIRED_SSLMODE + " is required");
        } else if (modes.isEmpty()) {
            problems.add(mixedCaseSslmode != null
                ? "spells sslmode as " + mixedCaseSslmode + ", which PgJDBC ignores (parameter names are case-sensitive)"
                : "has no sslmode");
        } else if (!REQUIRED_SSLMODE.equals(modes.getFirst())) {
            problems.add("has sslmode=" + modes.getFirst());
        }
        return problems;
    }

    /** The effective security.protocol of one client's built properties; records a problem unless it is a TLS protocol. */
    private static String checkKafkaProtocol(String client, Map<String, Object> clientProperties, List<String> problems) {
        Object configured = clientProperties.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        String protocol = configured == null || configured.toString().isBlank()
            ? KAFKA_DEFAULT_PROTOCOL : configured.toString().trim().toUpperCase(Locale.ROOT);
        if (!TLS_PROTOCOLS.contains(protocol)) {
            problems.add("the Kafka " + client + "'s effective " + KAFKA_PROTOCOL + " (KAFKA_SECURITY_PROTOCOL, spring.kafka.properties, spring.kafka."
                + client + ".*) is '" + protocol + "', not " + SASL_SSL + " or " + SSL + " (Strimzi mutual TLS), while a Kafka client is configured");
        }
        return protocol;
    }

    /** A Kafka client is configured when the context defines a template, producer factory, consumer factory or listener container factory. */
    static boolean hasKafkaClient(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(KafkaTemplate.class, true, false).length > 0
            || beanFactory.getBeanNamesForType(ProducerFactory.class, true, false).length > 0
            || hasKafkaConsumer(beanFactory);
    }

    /** A consumer exists when the context defines a consumer factory or a listener container factory. */
    static boolean hasKafkaConsumer(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(ConsumerFactory.class, true, false).length > 0
            || beanFactory.getBeanNamesForType(KafkaListenerContainerFactory.class, true, false).length > 0;
    }
}
