package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Round 5 common item 2 and round 6 (guardrail 4a, loan #14 review): the
 * service fails fast at start-up unless every datasource URL a pool can use
 * (spring.datasource.url, spring.datasource.hikari.jdbc-url, spring.flyway.url)
 * verifies the server certificate, read the way PgJDBC reads it (keys
 * case-sensitive, one sslmode, no verification-bypass key), and, when a Kafka
 * client is configured, the effective security.protocol of the producer (and
 * of the consumer when one exists) is SASL_SSL (MSK IAM) or SSL (Strimzi
 * mutual TLS); PLAINTEXT, SASL_PLAINTEXT and unset are refused.
 * fintechbankx.tls.enforce is true unless local or test configuration says
 * otherwise; the failure names the offending setting and never its value
 * when that value could carry a credential (the datasource URL).
 */
class TlsEnforcementTest {

    private static final String VERIFY_FULL_URL = "jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle_test"
        + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String VERIFY_FULL = "spring.datasource.url=" + VERIFY_FULL_URL;
    /** A JDBC URL may carry the credentials as query parameters; the stand-in value must never reach the message. */
    private static final String REQUIRE_URL =
        "jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle_test?sslmode=require&user=do-not-print";
    private static final String REQUIRE = "spring.datasource.url=" + REQUIRE_URL;

    private final ApplicationContextRunner service = new ApplicationContextRunner()
        .withUserConfiguration(TlsEnforcementConfiguration.class)
        .withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class));

    @Test
    void aDatasourceUrlWithSslmodeRequireStopsTheStart() {
        service.withPropertyValues(REQUIRE, "spring.kafka.security.protocol=SASL_SSL").run(context -> {
            assertThat(context).hasFailed();
            Throwable cause = root(context.getStartupFailure());
            assertThat(cause).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("DB_URL")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("fintechbankx.tls.enforce");
            // the URL may carry a credential: the message names the setting, not its value
            assertThat(cause.getMessage()).doesNotContain("do-not-print").doesNotContain("db.internal");
        });
    }

    @Test
    void aPlaintextKafkaClientStopsTheStart() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka.security.protocol")
                .hasMessageContaining("KAFKA_SECURITY_PROTOCOL")
                .hasMessageContaining("SASL_SSL")
                .hasMessageContaining("SSL (Strimzi mutual TLS)")
                .hasMessageContaining("PLAINTEXT");
        });
    }

    @Test
    void verifyFullAndSaslSslStart() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL")
            .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * SSL is Strimzi mutual TLS (profile kafka-strimzi, KafkaUser certificate): the
     * connection is encrypted and both sides verified, so it passes like SASL_SSL
     * does (the same set the payments, mandates, bulk and RtP services accept).
     */
    @Test
    void verifyFullAndSslMutualTlsStart() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SSL")
            .run(context -> assertThat(context).hasNotFailed());
        // spring.kafka.properties.security.protocol is what KafkaProperties applies last
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.properties.security.protocol=SSL")
            .run(context -> assertThat(context).hasNotFailed());
    }

    /** SASL without TLS authenticates in the clear; it is refused like PLAINTEXT. */
    @Test
    void aSaslPlaintextKafkaClientStopsTheStart() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka.security.protocol")
                .hasMessageContaining("SASL_PLAINTEXT")
                .hasMessageContaining("SASL_SSL")
                .hasMessageContaining("SSL");
        });
    }

    /** Anything that is not SASL_SSL or SSL is refused, wherever the protocol comes from. */
    @Test
    void aProtocolWithoutTlsIsRefusedWhenAKafkaClientIsConfigured() {
        // spring.kafka.properties.security.protocol is what KafkaProperties applies last
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.properties.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasFailed());
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SSL",
                "spring.kafka.properties.security.protocol=SASL_PLAINTEXT")
            .run(context -> assertThat(context).hasFailed());
        // no protocol at all means Kafka's default, PLAINTEXT
        service.withPropertyValues(VERIFY_FULL).run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure())).hasMessageContaining("PLAINTEXT");
        });
        // a blank value is unset too
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void withoutAKafkaClientOnlyTheDatasourceIsChecked() {
        ApplicationContextRunner noKafka = new ApplicationContextRunner()
            .withUserConfiguration(TlsEnforcementConfiguration.class);

        noKafka.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT")
            .run(context -> assertThat(context).hasNotFailed());
        noKafka.withPropertyValues(REQUIRE).run(context -> assertThat(context).hasFailed());
        noKafka.run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure())).hasMessageContaining("spring.datasource.url");
        });
    }

    /** Local runs and tests only (profile local, test resources); the chart never sets it. */
    @Test
    void enforceFalseSkipsBothChecks() {
        service.withPropertyValues(REQUIRE, "spring.kafka.security.protocol=PLAINTEXT", "fintechbankx.tls.enforce=false")
            .run(context -> assertThat(context).hasNotFailed());
    }

    /** The datasource URL is parsed the way PgJDBC parses it, so the last sslmode would win: two are refused outright. */
    @Test
    void aSecondSslmodeAfterVerifyFullStopsTheStart() {
        service.withPropertyValues(VERIFY_FULL + "&sslmode=disable", "spring.kafka.security.protocol=SASL_SSL").run(context -> {
            assertThat(context).hasFailed();
            assertThat(root(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("sslmode 2 times")
                .hasMessageContaining("sslmode=verify-full");
        });
    }

    /** PgJDBC keys are case-sensitive: SSLMODE is not sslmode, and the driver would fall back to its default. */
    @Test
    void anUpperCaseSslmodeIsNotAnSslmode() {
        service.withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db.internal:5432/d?SSLMODE=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem",
                "spring.kafka.security.protocol=SASL_SSL")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure()))
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining("SSLMODE")
                    .hasMessageContaining("case-sensitive");
            });
    }

    /** A verify-full URL with a key that replaces the certificate or host name check is refused. */
    @Test
    void aVerificationBypassKeyStopsTheStart() {
        for (String bypass : List.of("sslfactory=org.postgresql.ssl.NonValidatingFactory", "sslfactoryarg=x",
                "sslhostnameverifier=x.Y", "sslpasswordcallback=x.Y", "service=other")) {
            service.withPropertyValues(VERIFY_FULL + "&" + bypass, "spring.kafka.security.protocol=SASL_SSL").run(context -> {
                assertThat(context).as(bypass).hasFailed();
                assertThat(root(context.getStartupFailure())).as(bypass)
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining(bypass.substring(0, bypass.indexOf('=')));
            });
        }
    }

    /** sslmode=verify-full inside another parameter's value is that parameter's value, not an sslmode. */
    @Test
    void verifyFullInsideAnotherValueDoesNotCount() {
        service.withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://db.internal:5432/d?sslmode=require&applicationName=sslmode=verify-full",
                "spring.kafka.security.protocol=SASL_SSL")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure())).hasMessageContaining("sslmode=require");
            });
    }

    /** Hikari's own jdbc-url replaces spring.datasource.url once the pool is bound; Flyway's url gives it a second datasource. */
    @Test
    void everyDatasourceUrlAPoolCanUseIsChecked() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL", "spring.datasource.hikari.jdbc-url=" + REQUIRE_URL)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure()))
                    .hasMessageContaining("spring.datasource.hikari.jdbc-url")
                    .hasMessageContaining("SPRING_DATASOURCE_HIKARI_JDBC_URL");
                assertThat(root(context.getStartupFailure()).getMessage()).doesNotContain("do-not-print");
            });
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL", "spring.flyway.url=" + REQUIRE_URL)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure())).hasMessageContaining("spring.flyway.url");
            });
        // both set and both verify-full: fine
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.datasource.hikari.jdbc-url=" + VERIFY_FULL_URL, "spring.flyway.url=" + VERIFY_FULL_URL)
            .run(context -> assertThat(context).hasNotFailed());
    }

    /** The settings are read as the pool reads them: an environment variable in relaxed form binds jdbc-url too. */
    @Test
    void aRelaxedEnvironmentVariableReachesTheHikariCheck() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL")
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                // a source named like the OS environment, so Spring applies its environment-variable name mapping
                new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of("SPRING_DATASOURCE_HIKARI_JDBCURL", REQUIRE_URL))))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure())).hasMessageContaining("spring.datasource.hikari.jdbc-url");
            });
    }

    /** The producer's effective protocol is what KafkaProperties builds: the producer's own properties win over the common one. */
    @Test
    void aPlaintextProducerPropertyStopsTheStart() {
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.producer.properties.security.protocol=PLAINTEXT")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure())).hasMessageContaining("producer").hasMessageContaining("PLAINTEXT");
            });
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.properties.security.protocol=PLAINTEXT")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(root(context.getStartupFailure())).hasMessageContaining("PLAINTEXT");
            });
        // the producer's own SASL_SSL over a PLAINTEXT common setting is what the client uses
        service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT",
                "spring.kafka.producer.properties.security.protocol=SASL_SSL", "spring.kafka.producer.security.protocol=SASL_SSL")
            .run(context -> assertThat(context).hasNotFailed());
    }

    /** With a consumer in the context, its effective protocol is checked too; without one it is not. */
    @Test
    void aPlaintextConsumerStopsTheStartWhenAConsumerExists() {
        ApplicationContextRunner withConsumer = service
            .withBean("repaymentListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class,
                () -> mock(ConcurrentKafkaListenerContainerFactory.class));
        for (String consumer : List.of("spring.kafka.consumer.security.protocol=PLAINTEXT",
                "spring.kafka.consumer.properties.security.protocol=PLAINTEXT")) {
            withConsumer.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL", consumer).run(context -> {
                assertThat(context).as(consumer).hasFailed();
                assertThat(root(context.getStartupFailure())).as(consumer).hasMessageContaining("consumer").hasMessageContaining("PLAINTEXT");
            });
            service.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL", consumer)
                .run(context -> assertThat(context).as(consumer + " without a consumer").hasNotFailed());
        }
        new ApplicationContextRunner().withUserConfiguration(TlsEnforcementConfiguration.class)
            .withBean("consumerFactory", ConsumerFactory.class, () -> mock(ConsumerFactory.class))
            .withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SSL", "spring.kafka.consumer.security.protocol=SASL_PLAINTEXT")
            .run(context -> assertThat(context).hasFailed());
        withConsumer.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SSL")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theRulesAreCheckedAsPlainFunctionsToo() {
        String ca = "&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=verify-full" + ca)).isEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslrootcert=/x&sslmode=verify-full")).isEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=verify-full&ApplicationName=loan&sslmode")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=verify-ca")).hasSize(1).first().asString().contains("sslmode=verify-ca");
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=require")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?ssl=true")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/sslmode=verify-full")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?SslMode=verify-full")).first().asString().contains("SslMode");
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=verify-full&sslmode=verify-full")).first().asString().contains("2 times");
        assertThat(TlsEnforcement.datasourceProblems("jdbc:postgresql://h:5432/d?sslmode=verify-full&SSLFACTORY=x")).first().asString().contains("sslfactory");
        assertThat(TlsEnforcement.datasourceProblems("")).isNotEmpty();
        assertThat(TlsEnforcement.datasourceProblems(null)).isNotEmpty();
    }

    /** The deepest cause, or the failure itself: the assertion is thrown unwrapped from the post-processor. */
    private static Throwable root(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
