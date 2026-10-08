# RUNBOOK-EXTRACT-ln-loan-lifecycle

Extraction of the Loan aggregate from `enterprise-loan-management-system` into
`svc-ln-loan-lifecycle` (this repository), following the strangler-fig steps of
`fbx-monolith-extraction`. Status: **Proposed**.

| Field | Value |
|---|---|
| Context / service | `ln` / `svc-ln-loan-lifecycle` (`app.ln.loan-lifecycle`) |
| Slice | Loan aggregate: application, approval, disbursement, repayment, schedule, repayment history |
| Owned data | `db_ln_loan_lifecycle_<env>`, schema `sc_ln_loan_lifecycle`: `loan`, `loan_installment`, `repayment`, `repayment_allocation`, `inbox_message`, `credit_reservation_generation`, `outbox_event` |
| Publishes | `evt.ln.loan.{created,approved,rejected,disbursed,cancelled,payment-made,fully-paid}.v1`, DLQ `evt.ln.loan.dlq.v1` (`api/asyncapi/svc-ln-loan-lifecycle.yaml`; **catalog PR pending**, relay off until it merges) |
| Consumes | `evt.pay.payment.loan-payment-completed.v1`, group `cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1` (off until step 3) |
| Depends on | `svc-cus-profile-kyc`: `GET /api/v1/customers/{id}/credit`, `POST .../credit/reserve`, `POST .../credit/release` |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `loans` (V2) | this service, `loan` | FK to `customers` dropped; `customer_id` kept as text; flat rate kept (`rate_basis = FLAT_TOTAL`, `legacy_flat_rate`) |
| `loan_installments` (V3) | this service, `loan_installment` | split into principal and interest; triggers replaced by aggregate logic |
| `payments` (V4), COMPLETED rows | this service, `repayment` | the loan context owns how a repayment was applied; source `MONOLITH`, same id |
| `payment_installments` (V4) | this service, `repayment_allocation` | one row per installment settled; early-payment discount and late penalty per row |
| `payments` (V4), money movement and in-flight rows | `svc-pay-initiation-settlement` / stays in the monolith | INITIATED or PROCESSING payments finish in the monolith and are **not migrated** (step 2 refuses to run while any exist) |
| `update_updated_at_column`, `check_loan_fully_paid` triggers | removed | `Loan` keeps `updated_at` and status |
| `customers`, `customer_management` schema (V1) | `svc-cus-profile-kyc` | not copied; loans keep the id only |
| `loan_origination` schema (V2__Create_loan_origination_schema) | not in this slice | origination workflow tables (applications, monthly rates); owner to be decided with the Architecture Board; nothing is copied and nothing here reads it |
| `loan_applications`, `underwriters`, `loan_officers` (V10-V12) | not in this slice | origination workflow; same decision as above |
| `credit_reports`, `risk_assessments` (V14-V15) | `svc-rsk-decisioning` | separate slice |
| `compliance_reports` (V13) | `svc-cmp-evidence` | separate slice |
| `loan_service.*` (loan/V1) | not migrated | parallel schema from an earlier split attempt; confirm it has no rows before the cutover |

Migrations: `loan-infrastructure/src/main/resources/db/migration/V1..V3`. The service never reads
monolith tables and the monolith must not read `sc_ln_loan_lifecycle`.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<loan service conninfo>" <currency>`

- `<currency>` is **required** (ISO 4217): the monolith stores none and its code falls back to USD,
  so the book's currency is stated by whoever runs the migration. There is no default.
- Refuses to run while any monolith payment is INITIATED or PROCESSING.
- Exports `loans`, `loan_installments`, COMPLETED `payments` and their `payment_installments` in one
  read-only snapshot (no customer columns), stages and transforms them
  (`02_transform_into_loan_service.sql`, mapping listed at its top).
- **Delta-safe**: every run upserts the monolith-owned columns of loans the service has not changed
  since they were backfilled (`version = 0`; `source_updated_at`, `backfilled_at` record the run).
  Loans the service changed are never overwritten.
- Reconciles only monolith-owned rows (`03_reconcile.sql`): loan, installment, repayment and
  allocation counts and totals, flat rates, outstanding totals, per-loan invariants, and the
  **monthly payment the API reports against the monolith's first installment**. Any difference fails.

Rehearsal: `scripts/migration/verify-backfill.sh <currency>` (CI passes `XTS`): refusal without a
currency and with an in-flight payment, first run, then a monolith change plus a service-changed
loan, second run, delta checks.

## 3. Preconditions

| Precondition | Before | Why |
|---|---|---|
| Customer cutover (`RUNBOOK-EXTRACT-cus-profile-kyc`) through its step 6; its step 4 switched this service to `CUSTOMER_CREDIT_ADAPTER=http` | loan step 2 | one credit ledger: otherwise the monolith and the customer service both move `used_credit` |
| Payments slice ready to cut over in the same window, publishing `evt.pay.payment.loan-payment-completed.v1` | loan step 2 | loan and payments writes move together; repayments must land in exactly one place |
| `evt.ln.loan.*`, `evt.ln.loan.dlq.v1`, `evt.pay.payment.loan-payment-completed.v1` in the asyncapi catalog and created on the cluster (catalog PR pending) | step 3 (consumer), step 4 (relay) | the service never creates topics |
| ConfigMap `rds-ca-bundle` (key `global-bundle.pem`) published in namespace `lending` by trust-manager (mesh repo `k8s/platform/cert-manager/bundle-rds-ca.yaml`); `DB_URL` = Terraform output `jdbc_url` (`sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem`) | step 1 | the pods mount the bundle to verify Aurora's certificate; without it they do not start |
| Mesh ALLOW rules for the callers in README "Callers" | step 2 | namespace `lending` is default-deny |
| No INITIATED / PROCESSING monolith payments | step 2 final delta | in-flight repayments finish in the monolith |

## 4. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy with `OUTBOX_RELAY_ENABLED=false`, `LOAN_REPAYMENT_CONSUMER_ENABLED=false`; run the backfill; reconcile. Repeat any time before step 2 (delta-safe) | drop `sc_ln_loan_lifecycle`; nothing else changed |
| 2 | One window, loan and payments together: (a) **write freeze**: monolith loan and payment writes off (maintenance flag), wait until no payment is INITIATED or PROCESSING; (b) **final delta**: run the backfill; (c) **reconcile**: must pass; (d) **flip**: the monolith's anti-corruption client sends loan writes to this API, payments to `svc-pay-initiation-settlement` | before (d): lift the freeze, nothing moved. After (d): see section 5 |
| 3 | Enable the repayment consumer (`LOAN_REPAYMENT_CONSUMER_ENABLED=true`) in the same window as step 2 (d) | consumer off; unprocessed events wait on the topic (committed offsets) |
| 4 | Enable the outbox relay; consumers move to `evt.ln.loan.*.v1` | relay off; events stay in the outbox |
| 5 | Monolith stops writing `loans` / `loan_installments` / `payments` for good | flag back; monolith tables are intact but stale since step 2 (section 5) |
| 6 | After the payment cutover is final and one full month-end cycle passed: drop the monolith FKs first (`fk_payments_loan` on `payments`, `fk_payment_installments_installment`, then `fk_loans_customer`), then `payment_installments`, `loan_installments`, `loans` | restore from snapshot |

## 5. Rollback after step 2

The service is the system of record from step 2 (d). Rollback is a **forward fix** by default. If the
service has to be abandoned, it is a **reverse replay**: rebuild the monolith rows from
`evt.ln.loan.*.v1` (loan state and `payment-made` with `paymentId`) and `repayment` /
`repayment_allocation`, under a new write freeze; the outbox keeps every event since step 1 even
while the relay is off. Never simply flip the flag back: writes made in the service after the flip
would be lost.

Rollback triggers (measured from the start of step 2 (d), any one):

| Trigger | Threshold |
|---|---|
| 5xx rate on `/api/v1/loans/**` | > 1 % over 10 minutes |
| `CUSTOMER_SERVICE_UNAVAILABLE` (503) on disburse | > 5 % over 10 minutes |
| `consumer.dlq.messages{group=cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1}` | > 0 in the window |
| `outbox.parked.events` | > 0 |
| `outbox.oldest.pending.age` (once the relay is on) | > 300 s |
| Reconcile re-run against the frozen monolith snapshot | any `|f` line |

## 6. Acceptance checklist

- [x] Service builds and checks standalone (`ci/test` runs `./gradlew check` with PostgreSQL; ArchUnit rules; coverage)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order; poison rows parked after 10 attempts
- [x] Customer credit through the customer service API (no shared table); consumer contract test against customer-context.yaml
- [x] Delta backfill rehearsed with reconciliation in CI
- [ ] Monolith anti-corruption client and write-freeze flag (enterprise-loan-management-system)
- [ ] asyncapi catalog PR for `svc-ln-loan-lifecycle.yaml`; topics created (fintechbankx-platform-event-streaming-kafka)
- [ ] Mesh ALLOW rules for the callers in README
- [ ] Production backfill and reconciliation report attached here
