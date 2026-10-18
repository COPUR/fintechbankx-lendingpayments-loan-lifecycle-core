package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.UnresolvedBookingSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerUnresolvedBookingSignalTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MicrometerUnresolvedBookingSignal signal = new MicrometerUnresolvedBookingSignal(meters);

    @Test
    void eachCauseIsCountedUnderItsOwnTag() {
        signal.raise(LoanId.of("LOAN-SIG-1"), "loan-disbursement:LOAN-SIG-1",
            UnresolvedBookingSignal.Cause.LEDGER_UNANSWERED);
        signal.raise(LoanId.of("LOAN-SIG-2"), "loan-disbursement:LOAN-SIG-2",
            UnresolvedBookingSignal.Cause.NOT_STORED);
        signal.raise(LoanId.of("LOAN-SIG-3"), "loan-disbursement:LOAN-SIG-3",
            UnresolvedBookingSignal.Cause.NOT_STORED);

        assertThat(meters.counter(MicrometerUnresolvedBookingSignal.COUNTER, "cause", "ledger_unanswered").count())
            .isEqualTo(1.0);
        assertThat(meters.counter(MicrometerUnresolvedBookingSignal.COUNTER, "cause", "not_stored").count())
            .isEqualTo(2.0);
    }
}
