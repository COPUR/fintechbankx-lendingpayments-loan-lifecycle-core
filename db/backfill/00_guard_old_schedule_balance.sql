-- Step 0 of the loan data split, run by run-backfill.sh against the LOAN SERVICE database before anything is
-- staged. Refuses the backfill while a row holds a balance written under the old schedule rule, where the
-- balance was the schedule's total still due rather than the principal:
--   * a loan created here (rate_basis NOMINAL_ANNUAL) must hold its principal less the repayments applied;
--   * a migrated loan the service has changed (version > 0) must hold its principal less the principal part
--     paid on its installments (interest first).
-- Changes nothing. Nothing has checked the environments yet: each must pass this guard before cutover
-- (docs/migration runbook, section 2).

\set ON_ERROR_STOP on

DO $$
DECLARE
    stale integer;
BEGIN
    SELECT count(*) INTO stale
      FROM sc_ln_loan_lifecycle.loan l
     WHERE (l.legacy_loan_id IS NULL AND l.rate_basis = 'NOMINAL_ANNUAL'
            AND l.outstanding_balance <> l.principal_amount
                - coalesce((SELECT sum(r.amount) FROM sc_ln_loan_lifecycle.repayment r WHERE r.loan_id = l.loan_id), 0))
        OR (l.legacy_loan_id IS NOT NULL AND l.version > 0
            AND l.outstanding_balance <> l.principal_amount
                - coalesce((SELECT sum(greatest(i.paid_amount - i.interest_amount, 0))
                              FROM sc_ln_loan_lifecycle.loan_installment i WHERE i.loan_id = l.loan_id), 0));
    IF stale > 0 THEN
        RAISE EXCEPTION 'BACKFILL REFUSED: % loan(s) hold a balance written under the old schedule rule (runbook section 2)', stale;
    END IF;
END
$$;
