package com.bank.loan.infrastructure.persistence;

import com.bank.loan.domain.InstallmentAllocation;
import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.PaymentId;
import com.bank.loan.domain.Repayment;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit level: which rows the ledger writes. The SQL runs against
 * PostgreSQL in LoanLifecycleServiceIT.
 */
@SuppressWarnings("unchecked")
class JdbcRepaymentLedgerTest {

    private static final Instant APPLIED = Instant.parse("2026-10-08T06:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcRepaymentLedger ledger = new JdbcRepaymentLedger(jdbc);

    @Test
    void containsAsksForThePaymentId() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("PAY-1"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("PAY-2"))).thenReturn(0);

        assertThat(ledger.contains(PaymentId.of("PAY-1"))).isTrue();
        assertThat(ledger.contains(PaymentId.of("PAY-2"))).isFalse();
    }

    @Test
    void repaymentFoundByIdempotencyKeyIsMappedFromTheRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("payment_id")).thenReturn("PAY-9");
        when(rs.getString("loan_id")).thenReturn("LOAN-9");
        when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("100.0000"));
        when(rs.getString("currency")).thenReturn("AED");
        when(rs.getBoolean("loan_fully_paid")).thenReturn(false);
        when(rs.getTimestamp("applied_at")).thenReturn(Timestamp.from(APPLIED));
        when(rs.getString("source")).thenReturn("API");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("CUST-9"), eq("key-9"))).thenAnswer(invocation ->
            List.of(((RowMapper<Repayment>) invocation.getArgument(1)).mapRow(rs, 0)));

        Repayment found = ledger.findByIdempotencyKey("CUST-9", "key-9").orElseThrow();

        assertThat(found.paymentId()).isEqualTo(PaymentId.of("PAY-9"));
        assertThat(found.amount()).isEqualTo(Money.aed(new BigDecimal("100.00")));
        assertThat(found.idempotencyKey()).isEqualTo("key-9");
        assertThat(found.source()).isEqualTo(Repayment.Source.API);
    }

    @Test
    void recordWritesTheRepaymentAndOneAllocationRowPerInstallment() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.query(startsWith("select installment_number"), any(RowMapper.class), eq("LOAN-1")))
            .thenReturn(List.of(Map.entry(1, first), Map.entry(2, second)));
        when(jdbc.queryForObject(startsWith("select count(*) from loan_installment"), eq(Integer.class), any(Object[].class)))
            .thenReturn(1);
        Repayment repayment = new Repayment(PaymentId.of("PAY-1"), LoanId.of("LOAN-1"), aed("1100.00"), List.of(
            new InstallmentAllocation(1, aed("1032.80"), aed("972.80"), aed("60.00")),
            new InstallmentAllocation(2, aed("67.20"), aed("12.06"), aed("55.14"))),
            false, APPLIED, Repayment.Source.PAYMENT_EVENT, null, null);

        ledger.record(repayment);

        verify(jdbc).update(startsWith("insert into repayment "), eq("PAY-1"), eq("LOAN-1"),
            eq(new BigDecimal("1100.00")), eq("AED"), eq(1), eq(false), eq(Timestamp.from(APPLIED)),
            eq("PAYMENT_EVENT"), eq(null), eq(null));
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(startsWith("insert into repayment_allocation"), batch.capture());
        assertThat(batch.getValue()).hasSize(2);
        assertThat(batch.getValue().get(1)).containsExactly("PAY-1", second, "LOAN-1", 2, new BigDecimal("67.20"),
            new BigDecimal("12.06"), new BigDecimal("55.14"), "AED", Timestamp.from(APPLIED));
    }

    @Test
    void repaymentWithoutAScheduleWritesNoAllocations() {
        ledger.record(new Repayment(PaymentId.of("PAY-0"), LoanId.of("LOAN-0"), aed("5.00"), List.of(),
            false, APPLIED, Repayment.Source.API, "CUST-0", "k"));

        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void allocationToAnUnknownInstallmentFails() {
        when(jdbc.query(startsWith("select installment_number"), any(RowMapper.class), eq("LOAN-2"))).thenReturn(List.of());
        when(jdbc.queryForObject(startsWith("select count(*) from loan_installment"), eq(Integer.class), any(Object[].class)))
            .thenReturn(0);

        assertThatThrownBy(() -> ledger.record(new Repayment(PaymentId.of("PAY-2"), LoanId.of("LOAN-2"), aed("1.00"),
            List.of(new InstallmentAllocation(7, aed("1.00"), aed("1.00"), aed("0.00"))),
            false, APPLIED, Repayment.Source.API, null, null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no installment 7");
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
