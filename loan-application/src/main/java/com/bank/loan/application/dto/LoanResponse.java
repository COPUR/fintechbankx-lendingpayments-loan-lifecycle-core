package com.bank.loan.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A loan as the API returns it.
 *
 * <ul>
 *   <li>annualInterestRate: percent per year (6.5 = 6.5%). For rateBasis
 *       NOMINAL_ANNUAL (loans created here) it is the nominal rate,
 *       compounded monthly on the declining balance. For FLAT_TOTAL (loans
 *       migrated from the monolith) it is the flat total rate annualised for
 *       display: flatTotalRate * 100 * 12 / termInMonths.</li>
 *   <li>flatTotalRate: FLAT_TOTAL loans only, the monolith's interest_rate as
 *       a fraction of the principal over the whole term (0.200 = 20%); null
 *       otherwise. Parity checks compare it with the monolith's interestRate.</li>
 *   <li>outstandingBalance: everything still due on the schedule (principal
 *       plus scheduled interest). monthlyPayment: the first installment.</li>
 * </ul>
 */
public record LoanResponse(
    String loanId,
    String customerId,
    BigDecimal principalAmount,
    BigDecimal annualInterestRate,
    String rateBasis,
    BigDecimal flatTotalRate,
    String currency,
    Integer termInMonths,
    String status,
    LocalDate applicationDate,
    LocalDate approvalDate,
    LocalDate disbursementDate,
    LocalDate maturityDate,
    BigDecimal outstandingBalance,
    BigDecimal monthlyPayment,
    Instant createdAt,
    Instant lastModifiedAt
) {
    
    public static LoanResponse from(com.bank.loan.domain.Loan loan) {
        return new LoanResponse(
            loan.getId().getValue(),
            loan.getCustomerId().getValue(),
            loan.getPrincipalAmount().getAmount(),
            loan.getInterestRate().getAnnualRate(),
            loan.getRateBasis().name(),
            loan.getFlatTotalRate().orElse(null),
            loan.getPrincipalAmount().getCurrency().getCurrencyCode(),
            loan.getLoanTerm().getMonths(),
            loan.getStatus().name(),
            loan.getApplicationDate(),
            loan.getApprovalDate(),
            loan.getDisbursementDate(),
            loan.getMaturityDate(),
            loan.getOutstandingBalance().getAmount(),
            loan.calculateMonthlyPayment().getAmount(),
            loan.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant(),
            loan.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
        );
    }
}