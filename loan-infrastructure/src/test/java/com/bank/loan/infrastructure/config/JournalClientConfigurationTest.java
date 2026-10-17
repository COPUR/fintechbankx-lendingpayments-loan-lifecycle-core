package com.bank.loan.infrastructure.config;

import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.infrastructure.external.AccountsLedgerHttpAdapter;
import com.bank.loan.infrastructure.external.InMemoryJournalEntryAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * journal.adapter picks the implementation of {@link JournalEntryPort}: http
 * (the accounts ledger, the default) or in-memory (local runs and tests).
 */
class JournalClientConfigurationTest {

    /** The placeholder configurer is what makes an unresolvable ${...} fail, as it does under Spring Boot. */
    private final ApplicationContextRunner bare = new ApplicationContextRunner()
        .withBean(PropertySourcesPlaceholderConfigurer.class)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withBean(ClientRegistrationRepository.class, () -> Mockito.mock(ClientRegistrationRepository.class))
        .withBean(OAuth2AuthorizedClientService.class, () -> Mockito.mock(OAuth2AuthorizedClientService.class))
        .withInitializer(context -> context.getBeanFactory().setConversionService(new ApplicationConversionService()));
    private final ApplicationContextRunner runner = bare
        .withPropertyValues("journal.base-url=http://ledger")
        .withUserConfiguration(JournalClientConfiguration.class, InMemoryJournalEntryAdapter.class);

    @Test
    void theHttpAdapterIsTheDefaultAndTheExplicitHttpChoice() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(JournalEntryPort.class);
            assertThat(context.getBean(JournalEntryPort.class)).isInstanceOf(AccountsLedgerHttpAdapter.class);
        });
        runner.withPropertyValues("journal.adapter=http").run(context ->
            assertThat(context.getBean(JournalEntryPort.class)).isInstanceOf(AccountsLedgerHttpAdapter.class));
    }

    @Test
    void inMemoryReplacesTheHttpAdapter() {
        runner.withPropertyValues("journal.adapter=in-memory").run(context -> {
            assertThat(context).hasSingleBean(JournalEntryPort.class);
            assertThat(context.getBean(JournalEntryPort.class)).isInstanceOf(InMemoryJournalEntryAdapter.class);
            assertThat(context).doesNotHaveBean(AccountsLedgerHttpAdapter.class);
        });
    }

    @Test
    void theHttpAdapterNeedsABaseUrl() {
        bare.withUserConfiguration(JournalClientConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("journal.base-url");
        });
    }
}
