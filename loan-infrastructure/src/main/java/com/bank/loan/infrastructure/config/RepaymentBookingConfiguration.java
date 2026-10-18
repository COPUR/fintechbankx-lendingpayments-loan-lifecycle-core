package com.bank.loan.infrastructure.config;

import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.RepaymentBookings;
import com.bank.loan.infrastructure.external.PostgresAdvisoryLock;
import com.bank.loan.infrastructure.external.RepaymentBookingSweep;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

/**
 * Starts the repayment booking sweep: on start-up and then at a fixed delay,
 * so a booking the ledger could not take is retried without anyone retrying
 * the repayment. The sweep is always on; a repayment without its sweep would
 * never reach the ledger.
 */
@Configuration
@EnableScheduling
public class RepaymentBookingConfiguration {

    @Bean
    RepaymentBookingSweep repaymentBookingSweep(RepaymentBookings bookings, JournalEntryPort journal,
                                                JdbcTemplate jdbc,
                                                @Value("${loan.repayment-booking.sweep.batch-size:50}") int batchSize) {
        return new RepaymentBookingSweep(bookings, journal,
            new PostgresAdvisoryLock(jdbc, RepaymentBookingSweep.SWEEP_LOCK_KEY), batchSize);
    }

    /** Bookings parked for an operator (runbook section 10, "Repayments"). Alert on any value above zero. */
    @Bean
    Gauge repaymentBookingsParkedGauge(MeterRegistry registry, RepaymentBookings bookings) {
        return Gauge.builder("loan.repayment.bookings.parked", bookings, RepaymentBookings::countParked)
            .description("Repayment bookings parked for an operator")
            .register(registry);
    }

    /**
     * Age of the oldest booking still waiting for the ledger, 0 when none waits. Alert when it stays above
     * a few sweep intervals: the ledger is not answering, or the sweep is not running.
     */
    @Bean
    Gauge repaymentBookingOldestPendingGauge(MeterRegistry registry, RepaymentBookings bookings, Clock clock) {
        return Gauge.builder("loan.repayment.bookings.oldest.pending.age", bookings,
                b -> b.oldestPending().map(at -> (double) Duration.between(at, clock.instant()).toSeconds()).orElse(0.0))
            .baseUnit("seconds")
            .description("Age of the oldest repayment booking not yet in the ledger")
            .register(registry);
    }

    @Bean
    RepaymentBookingSchedule repaymentBookingSchedule(RepaymentBookingSweep sweep) {
        return new RepaymentBookingSchedule(sweep);
    }

    static class RepaymentBookingSchedule {

        private final RepaymentBookingSweep sweep;

        RepaymentBookingSchedule(RepaymentBookingSweep sweep) {
            this.sweep = sweep;
        }

        @Scheduled(initialDelayString = "${loan.repayment-booking.sweep.initial-delay:PT0S}",
                   fixedDelayString = "${loan.repayment-booking.sweep.interval:PT1M}")
        void sweep() {
            sweep.sweepOnce();
        }
    }
}
