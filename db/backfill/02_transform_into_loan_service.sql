-- Step 2 of the loan data split: copy the staged monolith snapshot into the
-- service's own tables. Run by run-backfill.sh against the LOAN SERVICE
-- database (db_ln_loan_lifecycle_<env>) after Flyway created
-- sc_ln_loan_lifecycle. Requires -v currency=<ISO 4217> (no default: the
-- monolith has no currency column).
--
-- Delta-safe: every run upserts the monolith-owned columns of rows the
-- service has NOT changed since they were backfilled (loan.version = 0), so
-- the final run inside the write freeze picks up every change made in the
-- monolith since the first run. Rows the service changed (version > 0) are
-- never overwritten; 03_reconcile.sql lists them instead of comparing them.
--
-- Mapping (docs/migration/RUNBOOK-EXTRACT-ln-loan-lifecycle.md, "Data mapping"):
--   loan_id / legacy_loan_id   = monolith loans.id (UUID text), so ids stay stable
--   customer_id                = monolith customer id as text (customer-profile-kyc keeps the same id)
--   rate_basis                 = FLAT_TOTAL (monolith interest is a flat total over the term)
--   legacy_flat_rate           = interest_rate as stored (fraction: 0.200 = 20% of principal over the term)
--   annual_interest_rate       = interest_rate * 100 * 12 / number_of_installments, 4 decimals
--                                (percent per year, display only; the API also returns flatTotalRate)
--   status                     = FULLY_PAID when is_paid, else DISBURSED (monolith loans were disbursed on creation)
--   installment_number         = order of due_date within the loan
--   installment_id             = md5('elms-installment:' || id), so re-runs hit the same keys
--   installment principal      = round(loan_amount / n, 2); the last installment takes the remainder
--   installment interest       = amount - principal
--   installment paid_amount    = amount once is_paid (early-payment discount / late penalty go to
--                                repayment_allocation), else the monolith paid_amount
--   outstanding_balance        = sum of what is still due on unpaid installments
--   repayment                  = monolith COMPLETED payments (source MONOLITH, same id)
--   repayment_allocation       = payment_installments, one row per installment the payment settled

\set ON_ERROR_STOP on

BEGIN;

WITH unpaid AS (
    SELECT loan_id, sum(CASE WHEN is_paid THEN 0 ELSE amount - paid_amount END) AS due
      FROM backfill_stage.loan_installments
     GROUP BY loan_id
)
INSERT INTO sc_ln_loan_lifecycle.loan AS t (
    loan_id, customer_id, principal_amount, currency, annual_interest_rate, rate_basis, legacy_flat_rate,
    term_months, status, application_date, approval_date, disbursement_date, maturity_date,
    outstanding_balance, created_at, updated_at, version, legacy_loan_id, source_updated_at, backfilled_at)
SELECT l.id,
       l.customer_id::text,
       l.loan_amount,
       :'currency',
       round(l.interest_rate * 100 * 12 / l.number_of_installments, 4),
       'FLAT_TOTAL',
       l.interest_rate,
       l.number_of_installments,
       CASE WHEN l.is_paid THEN 'FULLY_PAID' ELSE 'DISBURSED' END,
       l.create_date::date,
       l.create_date::date,
       l.create_date::date,
       (l.create_date + make_interval(months => l.number_of_installments))::date,
       coalesce(u.due, 0),
       l.created_at,
       l.updated_at,
       0,
       l.id,
       l.updated_at,
       now()
  FROM backfill_stage.loans l
  LEFT JOIN unpaid u ON u.loan_id = l.id
ON CONFLICT (legacy_loan_id) DO UPDATE
   SET customer_id          = excluded.customer_id,
       principal_amount     = excluded.principal_amount,
       currency             = excluded.currency,
       annual_interest_rate = excluded.annual_interest_rate,
       rate_basis           = excluded.rate_basis,
       legacy_flat_rate     = excluded.legacy_flat_rate,
       term_months          = excluded.term_months,
       status               = excluded.status,
       application_date     = excluded.application_date,
       approval_date        = excluded.approval_date,
       disbursement_date    = excluded.disbursement_date,
       maturity_date        = excluded.maturity_date,
       outstanding_balance  = excluded.outstanding_balance,
       updated_at           = excluded.updated_at,
       source_updated_at    = excluded.source_updated_at,
       backfilled_at        = excluded.backfilled_at
 WHERE t.version = 0;

WITH numbered AS (
    SELECT i.*,
           l.loan_amount,
           l.number_of_installments AS n,
           row_number() OVER (PARTITION BY i.loan_id ORDER BY i.due_date, i.id) AS installment_number
      FROM backfill_stage.loan_installments i
      JOIN backfill_stage.loans l ON l.id = i.loan_id
), split AS (
    SELECT numbered.*,
           CASE WHEN installment_number = n
                THEN loan_amount - round(loan_amount / n, 2) * (n - 1)
                ELSE round(loan_amount / n, 2) END AS principal
      FROM numbered
)
INSERT INTO sc_ln_loan_lifecycle.loan_installment AS t (
    installment_id, loan_id, installment_number, amount, principal_amount, interest_amount, paid_amount,
    currency, due_date, paid_at, status)
SELECT md5('elms-installment:' || s.id)::uuid,
       s.loan_id,
       s.installment_number,
       s.amount,
       s.principal,
       s.amount - s.principal,
       CASE WHEN s.is_paid THEN s.amount ELSE s.paid_amount END,
       :'currency',
       s.due_date,
       s.payment_date,
       CASE WHEN s.is_paid THEN 'PAID'
            WHEN s.paid_amount > 0 THEN 'PARTIALLY_PAID'
            ELSE 'PENDING' END
  FROM split s
  JOIN sc_ln_loan_lifecycle.loan l ON l.legacy_loan_id = s.loan_id
 WHERE l.version = 0
ON CONFLICT (installment_id) DO UPDATE
   SET amount           = excluded.amount,
       principal_amount = excluded.principal_amount,
       interest_amount  = excluded.interest_amount,
       paid_amount      = excluded.paid_amount,
       currency         = excluded.currency,
       due_date         = excluded.due_date,
       paid_at          = excluded.paid_at,
       status           = excluded.status;

INSERT INTO sc_ln_loan_lifecycle.repayment (
    payment_id, loan_id, amount, currency, installments_paid, discount_total, penalty_total, loan_fully_paid,
    applied_at, source, legacy_payment_id)
SELECT p.id, p.loan_id, p.payment_amount, :'currency', p.installments_paid, p.total_discount, p.total_penalty,
       p.is_loan_fully_paid, p.payment_date, 'MONOLITH', p.id
  FROM backfill_stage.payments p
  JOIN sc_ln_loan_lifecycle.loan l ON l.legacy_loan_id = p.loan_id
 WHERE l.version = 0
ON CONFLICT (payment_id) DO NOTHING;

INSERT INTO sc_ln_loan_lifecycle.repayment_allocation (
    payment_id, installment_id, loan_id, installment_number, amount, principal, interest, discount, penalty,
    currency, allocated_at)
SELECT p.id,
       t.installment_id,
       t.loan_id,
       t.installment_number,
       t.amount,
       t.principal_amount,
       t.interest_amount,
       greatest(t.amount - i.paid_amount, 0),
       greatest(i.paid_amount - t.amount, 0),
       :'currency',
       p.payment_date
  FROM backfill_stage.payment_installments pi
  JOIN backfill_stage.payments p ON p.id = pi.payment_id
  JOIN backfill_stage.loan_installments i ON i.id = pi.installment_id
  JOIN sc_ln_loan_lifecycle.loan_installment t ON t.installment_id = md5('elms-installment:' || pi.installment_id)::uuid
  JOIN sc_ln_loan_lifecycle.loan l ON l.loan_id = t.loan_id
 WHERE l.version = 0
ON CONFLICT (payment_id, installment_id) DO NOTHING;

COMMIT;
