package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.UnresolvedBookingSignal;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Counts {@code loan_disbursement_booking_unresolved_total{cause}} and writes a
 * WARN, with the loan id and the reference only: no accounts, no amounts, no
 * ledger text. The runbook's reconciliation (section 10) is triggered by both.
 */
@Component
public class MicrometerUnresolvedBookingSignal implements UnresolvedBookingSignal {

    static final String COUNTER = "loan_disbursement_booking_unresolved_total";

    private static final Logger log = LoggerFactory.getLogger(MicrometerUnresolvedBookingSignal.class);

    private final MeterRegistry meters;

    public MicrometerUnresolvedBookingSignal(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void raise(LoanId loanId, String reference, Cause cause) {
        meters.counter(COUNTER, "cause", cause.name().toLowerCase(Locale.ROOT)).increment();
        log.warn("Loan {} is not disbursed but its booking {} may exist in the accounts ledger ({}); "
            + "a retry replays it, reconciliation settles it", loanId.getValue(), reference, cause);
    }
}
