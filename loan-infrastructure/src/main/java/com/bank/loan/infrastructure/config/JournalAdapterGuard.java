package com.bank.loan.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses {@code journal.adapter=in-memory} unless the configuration allows it
 * ({@code fintechbankx.journal.allow-in-memory}: local and test only). The
 * in-memory journal books nothing the accounts ledger keeps, so a deployed pod on
 * it would disburse loans with no ledger entry at all (DevSecOps B-2). The chart
 * refuses the same settings (deploy/helm, loan.guardValues).
 */
@Configuration(proxyBeanMethods = false)
public class JournalAdapterGuard {

    public JournalAdapterGuard(@Value("${journal.adapter:http}") String adapter,
                               @Value("${fintechbankx.journal.allow-in-memory:false}") boolean allowInMemory) {
        if ("in-memory".equals(adapter) && !allowInMemory) {
            throw new IllegalStateException("journal.adapter=in-memory is refused: a deployed service books in the "
                + "accounts ledger (http); in-memory is for local and test runs only "
                + "(fintechbankx.journal.allow-in-memory)");
        }
    }
}
