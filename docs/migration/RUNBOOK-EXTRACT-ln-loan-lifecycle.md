# RUNBOOK-EXTRACT-ln-loan-lifecycle

Extraction of the Loan aggregate from `enterprise-loan-management-system` into
`svc-ln-loan-lifecycle` (this repository), following the strangler-fig steps of
`fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `ln` / `svc-ln-loan-lifecycle` |
| Slice | Loan aggregate: application, approval, disbursement, repayment, schedule |
| Owned data | `db_ln_loan_lifecycle_<env>`, schema `sc_ln_loan_lifecycle`: `loan`, `loan_installment`, `outbox_event` |
| Events | `evt.ln.loan.{created,approved,rejected,disbursed,cancelled,payment-made,fully-paid}.v1` (AsyncAPI `svc-ln-loan-lifecycle.yaml` in the asyncapi catalog) |
| Depends on | `svc-cus-profile-kyc` HTTP API for credit check, reserve and release |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `loans` (V2) | this service, `loan` | FK to `customers` dropped; `customer_id` kept as text |
| `loan_installments` (V3) | this service, `loan_installment` | triggers replaced by aggregate logic |
| `update_updated_at_column`, `check_loan_fully_paid` triggers | removed | `Loan` keeps `updated_at` and status |
| `customers`, `customer_management` schema (V1) | `svc-cus-profile-kyc` | not copied; loans keep the id only |
| `payments` (V4), `payment_processing` schema (V3) | `svc-pay-initiation-settlement` | separate slice |
| `loan_applications`, `underwriters`, `loan_officers` (V10-V12) | not in this slice | origination workflow; owner to be decided with the Architecture Board |
| `credit_reports`, `risk_assessments` (V14-V15) | `svc-rsk-decisioning` | separate slice |
| `compliance_reports` (V13) | `svc-cmp-evidence` | separate slice |
| `loan_service.*` (loan/V1) | not migrated | parallel schema from an earlier split attempt; confirm it has no rows before the cutover |

Flyway migrations for the owned tables: `loan-infrastructure/src/main/resources/db/migration/V1__create_loan_tables.sql`, `V2__create_outbox.sql`. The service never reads monolith tables and the monolith must not read `sc_ln_loan_lifecycle`.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<loan service conninfo>" AED`

1. Exports `loans` and `loan_installments` in one read-only snapshot (CSV, no customer columns).
2. Stages them in `backfill_stage` in the service database and transforms them (`02_transform_into_loan_service.sql`). The mapping is listed at the top of that file; the notable ones are the interest rate (fraction to percent) and `outstanding_balance` (sum of unpaid installments).
3. Compares row counts, principal totals, scheduled and paid totals, and per-loan invariants (`03_reconcile.sql`). Any difference fails the run.

The script is idempotent (legacy ids and deterministic installment ids). `scripts/migration/verify-backfill.sh` rehearses it on a scratch PostgreSQL and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with `OUTBOX_RELAY_ENABLED=false`; run the backfill; reconcile | drop `sc_ln_loan_lifecycle`, nothing else changed |
| 2 | Monolith: route loan writes through an anti-corruption client to this API behind a flag (follow-up PR in the monolith) | flag off |
| 3 | Re-run the backfill for rows written before the flag flipped; reconcile again | flag off |
| 4 | Enable the outbox relay; consumers move to `evt.ln.loan.*.v1` | relay off; events stay in the outbox |
| 5 | Monolith stops writing `loans` / `loan_installments` | flag off, monolith tables are still intact |
| 6 | After one full month-end cycle: drop the monolith tables | restore from snapshot |

## 4. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order with one active relay
- [x] Customer credit through the customer service API (no shared table)
- [x] Backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] Monolith anti-corruption client behind a flag (enterprise-loan-management-system)
- [ ] Topics `evt.ln.loan.*.v1` created on the platform cluster (fintechbankx-platform-event-streaming-kafka)
- [ ] Production backfill and reconciliation report attached here
