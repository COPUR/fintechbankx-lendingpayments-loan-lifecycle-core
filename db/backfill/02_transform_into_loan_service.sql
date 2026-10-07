-- Step 2 of the loan data split: copy the staged monolith rows into the
-- service's own tables. Run by run-backfill.sh against the LOAN SERVICE
-- database (db_ln_loan_lifecycle_<env>) after Flyway created
-- sc_ln_loan_lifecycle. Idempotent: loans already loaded (same legacy id) and
-- their installments are skipped, so a partial run can simply be repeated.
--
-- Mapping decisions (see docs/migration/RUNBOOK-EXTRACT-ln-loan-lifecycle.md):
--   loan_id / legacy_loan_id  = monolith loans.id (UUID text), so ids stay stable
--   customer_id               = monolith customer id as text (customer-profile-kyc keeps the same id)
--   annual_interest_rate      = interest_rate * 100 (the monolith stored a fraction)
--   status                    = FULLY_PAID when is_paid, else DISBURSED (monolith loans were disbursed on creation)
--   outstanding_balance       = sum of unpaid installment amounts (what the customer still owes)
--   installment_number        = order of due_date within the loan
--   installment_id            = md5 of the monolith installment id, so re-runs hit the same keys

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO sc_ln_loan_lifecycle.loan (
    loan_id, customer_id, principal_amount, currency, annual_interest_rate, term_months, status,
    application_date, approval_date, disbursement_date, maturity_date, outstanding_balance,
    created_at, updated_at, version, legacy_loan_id)
SELECT l.id,
       l.customer_id::text,
       l.loan_amount,
       :'currency',
       l.interest_rate * 100,
       l.number_of_installments,
       CASE WHEN l.is_paid THEN 'FULLY_PAID' ELSE 'DISBURSED' END,
       l.create_date::date,
       l.create_date::date,
       l.create_date::date,
       (l.create_date + make_interval(months => l.number_of_installments))::date,
       coalesce((SELECT sum(i.amount - i.paid_amount) FROM backfill_stage.loan_installments i WHERE i.loan_id = l.id), 0),
       l.created_at,
       l.updated_at,
       0,
       l.id
  FROM backfill_stage.loans l
ON CONFLICT (legacy_loan_id) DO NOTHING;

INSERT INTO sc_ln_loan_lifecycle.loan_installment (
    installment_id, loan_id, installment_number, amount, paid_amount, currency,
    due_date, paid_at, status)
SELECT md5('elms-installment:' || i.id)::uuid,
       i.loan_id,
       row_number() OVER (PARTITION BY i.loan_id ORDER BY i.due_date, i.id),
       i.amount,
       i.paid_amount,
       :'currency',
       i.due_date,
       i.payment_date,
       CASE WHEN i.is_paid THEN 'PAID'
            WHEN i.paid_amount > 0 THEN 'PARTIALLY_PAID'
            ELSE 'PENDING' END
  FROM backfill_stage.loan_installments i
  JOIN sc_ln_loan_lifecycle.loan l ON l.legacy_loan_id = i.loan_id
ON CONFLICT (installment_id) DO NOTHING;

COMMIT;
