package com.bank.loan.domain;

/**
 * How a loan's interest rate is to be read.
 *
 * <ul>
 *   <li>{@link #NOMINAL_ANNUAL}: annual percentage, compounded monthly on the
 *       declining balance (amortised schedule). Every loan created by this
 *       service uses it.</li>
 *   <li>{@link #FLAT_TOTAL}: the monolith's rule. The rate was a fraction of
 *       the principal charged once over the whole term and spread evenly over
 *       the installments. Only loans migrated from enterprise-loan-management-system
 *       carry it; their schedule is taken as it was stored, never recalculated.</li>
 * </ul>
 */
public enum RateBasis {
    NOMINAL_ANNUAL,
    FLAT_TOTAL
}
