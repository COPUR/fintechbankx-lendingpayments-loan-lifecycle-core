package com.bank.loan.infrastructure.config;

import com.bank.loan.domain.port.out.JournalEntryPort;
import com.bank.loan.domain.port.out.RepaymentBookings;
import com.bank.loan.infrastructure.external.PostgresAdvisoryLock;
import com.bank.loan.infrastructure.external.RepaymentBookingSweep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
