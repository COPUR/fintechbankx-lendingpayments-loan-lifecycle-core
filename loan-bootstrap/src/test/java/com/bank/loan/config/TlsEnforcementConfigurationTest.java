package com.bank.loan.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 5 common item 2: fintechbankx.tls.enforce (TlsEnforcement) is true in
 * application.yml. Only explicit local and test configuration switches it off:
 * the local profile (application-local.yml) and the test resources of this
 * module (application.properties). The chart never sets it, and refuses the
 * env key and the local profile (deploy/helm, loan.guardEnvKey).
 */
class TlsEnforcementConfigurationTest {

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

    /** The integration tests boot against a plain local PostgreSQL and a mocked Kafka client. */
    @Test
    void theTestResourcesSwitchItOffForTheIntegrationTests() throws Exception {
        PropertySource<?> tests = new PropertiesPropertySourceLoader()
            .load("application.properties", new ClassPathResource("application.properties")).getFirst();

        assertThat(tests.getProperty("fintechbankx.tls.enforce")).isEqualTo("false");
    }
}
