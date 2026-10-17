-- Step 3 of the loan data split, run by run-backfill.sh against the LOAN
-- SERVICE database while backfill_stage still holds the monolith snapshot.
--
-- Compares only monolith-owned rows: loans the service has not changed since
-- the backfill (version = 0). Loans the service changed are listed under
-- "service_changed" and left out of both sides.
--
-- Output: one line per check, "check|monolith|loan service|ok". run-backfill.sh
-- fails if any line ends in "|f".

\set ON_ERROR_STOP on

WITH owned AS (
    SELECT legacy_loan_id AS id FROM sc_ln_loan_lifecycle.loan WHERE legacy_loan_id IS NOT NULL AND version = 0
), src_loans AS (
    SELECT l.* FROM backfill_stage.loans l
     WHERE l.id IN (SELECT id FROM owned)
        OR l.id NOT IN (SELECT legacy_loan_id FROM sc_ln_loan_lifecycle.loan WHERE legacy_loan_id IS NOT NULL)
), dst_loans AS (
    SELECT l.* FROM sc_ln_loan_lifecycle.loan l WHERE l.legacy_loan_id IN (SELECT id FROM owned)
), src_inst AS (
    SELECT i.* FROM backfill_stage.loan_installments i WHERE i.loan_id IN (SELECT id FROM src_loans)
), dst_inst AS (
    SELECT i.* FROM sc_ln_loan_lifecycle.loan_installment i WHERE i.loan_id IN (SELECT loan_id FROM dst_loans)
), src_pay AS (
    SELECT p.* FROM backfill_stage.payments p WHERE p.loan_id IN (SELECT id FROM src_loans)
), dst_pay AS (
    SELECT r.* FROM sc_ln_loan_lifecycle.repayment r
     WHERE r.source = 'MONOLITH' AND r.loan_id IN (SELECT loan_id FROM dst_loans)
), src_first AS (
    SELECT DISTINCT ON (loan_id) loan_id, amount FROM src_inst ORDER BY loan_id, due_date, id
), dst_first AS (
    SELECT loan_id, amount FROM dst_inst WHERE installment_number = 1
), checks(name, monolith, service) AS (
    SELECT 'loans', (SELECT count(*)::text FROM src_loans), (SELECT count(*)::text FROM dst_loans)
    UNION ALL
    SELECT 'principal_total', (SELECT coalesce(sum(loan_amount), 0)::numeric(19,2)::text FROM src_loans),
                              (SELECT coalesce(sum(principal_amount), 0)::numeric(19,2)::text FROM dst_loans)
    UNION ALL
    SELECT 'paid_loans', (SELECT count(*)::text FROM src_loans WHERE is_paid),
                         (SELECT count(*)::text FROM dst_loans WHERE status = 'FULLY_PAID')
    UNION ALL
    SELECT 'flat_rate', (SELECT coalesce(sum(interest_rate), 0)::numeric(19,3)::text FROM src_loans),
                        (SELECT coalesce(sum(legacy_flat_rate), 0)::numeric(19,3)::text FROM dst_loans)
    UNION ALL
    SELECT 'installments', (SELECT count(*)::text FROM src_inst), (SELECT count(*)::text FROM dst_inst)
    UNION ALL
    SELECT 'scheduled_total', (SELECT coalesce(sum(amount), 0)::numeric(19,2)::text FROM src_inst),
                              (SELECT coalesce(sum(amount), 0)::numeric(19,2)::text FROM dst_inst)
    UNION ALL
    SELECT 'settled_total', (SELECT coalesce(sum(CASE WHEN is_paid THEN amount ELSE paid_amount END), 0)::numeric(19,2)::text FROM src_inst),
                            (SELECT coalesce(sum(paid_amount), 0)::numeric(19,2)::text FROM dst_inst)
    UNION ALL
    SELECT 'outstanding_total', (SELECT coalesce(sum(CASE WHEN is_paid THEN 0 ELSE amount - paid_amount END), 0)::numeric(19,2)::text FROM src_inst),
                                (SELECT coalesce(sum(outstanding_balance), 0)::numeric(19,2)::text FROM dst_loans)
    UNION ALL
    SELECT 'repayments', (SELECT count(*)::text FROM src_pay), (SELECT count(*)::text FROM dst_pay)
    UNION ALL
    SELECT 'repayment_total', (SELECT coalesce(sum(payment_amount), 0)::numeric(19,2)::text FROM src_pay),
                              (SELECT coalesce(sum(amount), 0)::numeric(19,2)::text FROM dst_pay)
    UNION ALL
    SELECT 'allocations', (SELECT count(*)::text FROM backfill_stage.payment_installments pi WHERE pi.payment_id IN (SELECT id FROM src_pay)),
                          (SELECT count(*)::text FROM sc_ln_loan_lifecycle.repayment_allocation a WHERE a.payment_id IN (SELECT payment_id FROM dst_pay))
    UNION ALL
    -- The API reports monthlyPayment as the first installment; it must equal the monolith's.
    SELECT 'monthly_payment_mismatches', '0',
           (SELECT count(*)::text FROM src_first s JOIN dst_first d ON d.loan_id = s.loan_id WHERE d.amount <> s.amount)
    UNION ALL
    -- Per-loan invariants (expected 0 loans breaking them).
    SELECT 'loans_breaking_invariants', '0', (SELECT count(*)::text FROM (
        SELECT l.loan_id
          FROM dst_loans l
          LEFT JOIN dst_inst i ON i.loan_id = l.loan_id
         GROUP BY l.loan_id, l.term_months, l.outstanding_balance, l.principal_amount
        HAVING count(i.*) <> l.term_months
            OR l.outstanding_balance <> coalesce(sum(i.amount - i.paid_amount), 0)
            OR l.principal_amount <> coalesce(sum(i.principal_amount), 0)) broken)
    UNION ALL
    SELECT 'service_changed', (SELECT count(*)::text FROM sc_ln_loan_lifecycle.loan WHERE legacy_loan_id IS NOT NULL AND version > 0),
           (SELECT count(*)::text FROM sc_ln_loan_lifecycle.loan WHERE legacy_loan_id IS NOT NULL AND version > 0)
)
SELECT name || '|' || monolith || '|' || service || '|' || CASE WHEN monolith = service THEN 't' ELSE 'f' END
  FROM checks;
