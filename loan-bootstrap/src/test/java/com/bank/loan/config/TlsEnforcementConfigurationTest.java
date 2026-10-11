package com.bank.loan.config;

import com.bank.loan.infrastructure.config.TlsEnforcementConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Round 5 common item 2: fintechbankx.tls.enforce (TlsEnforcement) is true in
 * application.yml. Only explicit local and test configuration switches it off:
 * the local profile (application-local.yml) and the test resources of this
 * module (application.properties). The chart never sets it, and refuses the
 * env key and the local profile (deploy/helm, _helpers.tpl loan.guardValues).
 */
class TlsEnforcementConfigurationTest {

    private static final String VERIFY_FULL =
        "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_ln_loan_lifecycle"
            + "?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";

    @Test
    void theServiceEnforcesTlsByDefault() throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getProperty("fintechbankx.tls.enforce")).isEqualTo(true);
        // no other document or placeholder of the shipped configuration switches it off
        assertThat(String.valueOf(documents.getFirst().getProperty("fintechbankx.tls.enforce"))).doesNotContain("${");
    }

    @Test
    void onlyTheLocalProfileSwitchesItOff() throws Exception {
        PropertySource<?> local = new YamlPropertySourceLoader()
            .load("application-local.yml", new ClassPathResource("application-local.yml")).getFirst();
        PropertySource<?> msk = new YamlPropertySourceLoader()
            .load("application-kafka-msk.yml", new ClassPathResource("application-kafka-msk.yml")).getFirst();
        PropertySource<?> strimzi = new YamlPropertySourceLoader()
            .load("application-kafka-strimzi.yml", new ClassPathResource("application-kafka-strimzi.yml")).getFirst();

        assertThat(local.getProperty("fintechbankx.tls.enforce")).isEqualTo(false);
        assertThat(msk.getProperty("fintechbankx.tls.enforce")).isNull();
        assertThat(strimzi.getProperty("fintechbankx.tls.enforce")).isNull();
    }

    /**
     * The two shipped Kafka profiles pass the assertion as they are: kafka-msk
     * (SASL_SSL, IAM) and kafka-strimzi (SSL, mutual TLS with the KafkaUser
     * certificate). The profile's own file is the property source, so a change
     * to either file that drops TLS fails here.
     */
    @Test
    void theShippedKafkaProfilesPassTheKafkaAssertion() throws Exception {
        for (String profile : List.of("application-kafka-msk.yml", "application-kafka-strimzi.yml")) {
            PropertySource<?> kafkaProfile = new YamlPropertySourceLoader()
                .load(profile, new ClassPathResource(profile)).getFirst();
            new ApplicationContextRunner()
                .withUserConfiguration(TlsEnforcementConfiguration.class)
                .withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(kafkaProfile))
                .withPropertyValues(VERIFY_FULL)
                .run(context -> assertThat(context).as(profile).hasNotFailed());
        }
    }

    /** Without a Kafka profile the service has no protocol, which is PLAINTEXT: the assertion must still refuse it. */
    @Test
    void withoutAKafkaProfileTheKafkaAssertionFails() {
        new ApplicationContextRunner()
            .withUserConfiguration(TlsEnforcementConfiguration.class)
            .withBean("kafkaTemplate", KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withPropertyValues(VERIFY_FULL)
            .run(context -> assertThat(context).hasFailed());
    }

    /** The integration tests boot against a plain local PostgreSQL and a mocked Kafka client. */
    @Test
    void theTestResourcesSwitchItOffForTheIntegrationTests() throws Exception {
        PropertySource<?> tests = new PropertiesPropertySourceLoader()
            .load("application.properties", new ClassPathResource("application.properties")).getFirst();

        assertThat(tests.getProperty("fintechbankx.tls.enforce")).isEqualTo("false");
    }
}
