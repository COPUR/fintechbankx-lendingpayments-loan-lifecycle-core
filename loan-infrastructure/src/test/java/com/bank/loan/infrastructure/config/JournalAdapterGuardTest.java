package com.bank.loan.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JournalAdapterGuardTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(JournalAdapterGuard.class);

    @Test
    void httpIsTheDefaultAndStarts() {
        context.run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void inMemoryStopsTheStartWhenNotAllowed() {
        context.withPropertyValues("journal.adapter=in-memory").run(started -> {
            assertThat(started).hasFailed();
            assertThat(started.getStartupFailure()).rootCause()
                .hasMessageContaining("journal.adapter=in-memory is refused");
        });
    }

    @Test
    void inMemoryStartsOnlyWhenTheLocalOrTestConfigurationAllowsIt() {
        context.withPropertyValues("journal.adapter=in-memory", "fintechbankx.journal.allow-in-memory=true")
            .run(started -> assertThat(started).hasNotFailed());
    }
}
