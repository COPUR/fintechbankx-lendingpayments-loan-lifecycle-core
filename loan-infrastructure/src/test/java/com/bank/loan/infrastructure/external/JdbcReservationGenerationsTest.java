package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcReservationGenerationsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JdbcReservationGenerations generations = new JdbcReservationGenerations(jdbc, transactions);

    @Test
    void aLoanWithoutCompensationsIsAtGenerationZero() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("LOAN-G1"))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq("LOAN-G2"))).thenReturn(List.of(2));

        assertThat(generations.current(LoanId.of("LOAN-G1"))).isZero();
        assertThat(generations.current(LoanId.of("LOAN-G2"))).isEqualTo(2);
    }

    @Test
    void advancingRunsInItsOwnTransaction() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        generations.advance(LoanId.of("LOAN-G3"));

        verify(transactions).getTransaction(argThat(definition ->
            definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(jdbc).update(startsWith("insert into credit_reservation_generation"), eq("LOAN-G3"));
    }
}
