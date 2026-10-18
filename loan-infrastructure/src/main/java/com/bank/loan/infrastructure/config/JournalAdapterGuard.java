package com.bank.loan.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses {@code journal.adapter=in-memory} unless the configuration allows it
 * ({@code fintechbankx.journal.allow-in-memory}) and the startup TLS assertion is
 * off ({@code fintechbankx.tls.enforce=false}): local and test only. The
 * in-memory journal books nothing the accounts ledger keeps, so a deployed pod on
 * it would disburse loans with no ledger entry at all (DevSecOps B-2). The
 * allowance alone is not enough because whoever sets {@code journal.adapter} can
 * usually set it too; the chart refuses {@code fintechbankx.tls.*} on every route,
 * JVM options included, so a deployed pod always has {@code tls.enforce=true}.
 * The chart also refuses the journal settings themselves (deploy/helm,
 * loan.guardValues).
 */
@Configuration(proxyBeanMethods = false)
public class JournalAdapterGuard {

    public JournalAdapterGuard(@Value("${journal.adapter:http}") String adapter,
                               @Value("${fintechbankx.journal.allow-in-memory:false}") boolean allowInMemory,
                               @Value("${fintechbankx.tls.enforce:true}") boolean tlsEnforced) {
        if ("in-memory".equals(adapter) && (!allowInMemory || tlsEnforced)) {
            throw new IllegalStateException("journal.adapter=in-memory is refused: a deployed service books in the "
                + "accounts ledger (http); in-memory is for local and test runs only "
                + "(fintechbankx.journal.allow-in-memory with fintechbankx.tls.enforce=false)");
        }
    }
}
