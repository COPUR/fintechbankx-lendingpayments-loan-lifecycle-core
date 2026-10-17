package com.bank.loan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal-entry adapter to the accounts ledger (ADR-030) is chosen by
 * journal.adapter and pointed at the ledger by journal.base-url; the chart sets
 * JOURNAL_ADAPTER and ACCOUNTS_SERVICE_BASE_URL.
 */
class JournalConfigurationTest {

    @Test
    void theLedgerIsCalledOverHttpByDefault() throws Exception {
        Binder env = binder(Map.of());

        assertThat(env.bind("journal.adapter", String.class).get()).isEqualTo("http");
        assertThat(env.bind("journal.base-url", String.class).get())
            .isEqualTo("http://accounts-ledger-service.corebanking.svc.cluster.local:8080");
        assertThat(env.bind("journal.client-registration-id", String.class).get()).isEqualTo("accounts-ledger");
        // Same client-credentials path as the customer-service call: this service's own client, never the caller's token.
        assertThat(env.bind("spring.security.oauth2.client.registration.accounts-ledger.authorization-grant-type", String.class).get())
            .isEqualTo("client_credentials");
        assertThat(env.bind("spring.security.oauth2.client.registration.accounts-ledger.client-id", String.class).get())
            .isEqualTo("svc-ln-loan-lifecycle");
        assertThat(env.bind("spring.security.oauth2.client.registration.accounts-ledger.provider", String.class).get())
            .isEqualTo("fintechbankx");
    }

    @Test
    void theChartOverridesTheAdapterAndTheBaseUrl() throws Exception {
        Binder env = binder(Map.of(
            "JOURNAL_ADAPTER", "in-memory",
            "ACCOUNTS_SERVICE_BASE_URL", "http://127.0.0.1:18080"));

        assertThat(env.bind("journal.adapter", String.class).get()).isEqualTo("in-memory");
        assertThat(env.bind("journal.base-url", String.class).get()).isEqualTo("http://127.0.0.1:18080");
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
