package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Round 5 common item 2: the service fails fast at start-up unless the
 * datasource URL verifies the server certificate (sslmode=verify-full) and,
 * when a Kafka client is configured, the Kafka security protocol is SASL_SSL
 * (MSK IAM) or SSL (Strimzi mutual TLS); PLAINTEXT, SASL_PLAINTEXT and unset
 * are refused. fintechbankx.tls.enforce is true unless local or test configuration says
 * otherwise; the failure names the offending setting and never its value
 * when that value could carry a credential (the datasource URL).
 */
class TlsEnforcementTest {

    private static final String VERIFY_FULL =
        "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle_test"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    /** A JDBC URL may carry the credentials as query parameters; the stand-in value must never reach the message. */
    private static final String REQUIRE =
        "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle_test?sslmode=require&user=do-not-print";

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

    @Test
    void theRulesAreCheckedAsPlainFunctionsToo() {
        assertThat(TlsEnforcement.verifiesServerCertificate(
            "jdbc:postgresql://h:5432/d?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem")).isTrue();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/d?sslrootcert=/x&sslmode=verify-full")).isTrue();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/d?sslmode=verify-ca")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/d?sslmode=require")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/d?ssl=true")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/d")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate("jdbc:postgresql://h:5432/sslmode=verify-full")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate("")).isFalse();
        assertThat(TlsEnforcement.verifiesServerCertificate(null)).isFalse();
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
