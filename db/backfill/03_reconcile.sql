-- Step 3 of the loan data split, run by run-backfill.sh against the LOAN
-- SERVICE database. Rows 1-2 are totals compared with the monolith (loans,
-- then installments); any further row is a loan breaking an invariant.

\set ON_ERROR_STOP on

SELECT count(*)                                        AS loan_rows,
       coalesce(sum(principal_amount), 0)::numeric(19,2) AS principal_total,
       count(*) FILTER (WHERE status = 'FULLY_PAID')   AS paid_loans
  FROM sc_ln_loan_lifecycle.loan
 WHERE legacy_loan_id IS NOT NULL;

SELECT count(*)                                  AS installment_rows,
       coalesce(sum(i.amount), 0)::numeric(19,2)      AS scheduled_total,
       coalesce(sum(i.paid_amount), 0)::numeric(19,2) AS paid_total
  FROM sc_ln_loan_lifecycle.loan_installment i
  JOIN sc_ln_loan_lifecycle.loan l ON l.loan_id = i.loan_id
 WHERE l.legacy_loan_id IS NOT NULL;

-- Per-loan invariants that must hold for every migrated loan (expect 0 rows).
SELECT l.loan_id, l.term_months, count(i.*) AS installments,
       l.outstanding_balance, coalesce(sum(i.amount - i.paid_amount), 0) AS unpaid
  FROM sc_ln_loan_lifecycle.loan l
  LEFT JOIN sc_ln_loan_lifecycle.loan_installment i ON i.loan_id = l.loan_id
 WHERE l.legacy_loan_id IS NOT NULL
 GROUP BY l.loan_id, l.term_months, l.outstanding_balance
HAVING count(i.*) <> l.term_months
    OR l.outstanding_balance <> coalesce(sum(i.amount - i.paid_amount), 0);
