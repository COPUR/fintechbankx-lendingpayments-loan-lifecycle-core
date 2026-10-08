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

Migrations: `loan-infrastructure/src/main/resources/db/migration/V1..V6`. The service never reads
monolith tables and the monolith must not read `sc_ln_loan_lifecycle`.

### Database roles

| Role | Secret (`{"username","password"}`) | Used by | Privileges |
|---|---|---|---|
| schema owner `loan_lifecycle_owner` | `<env>/loan-lifecycle-service/db-migration` (Terraform output `migration_db_secret_name`, Helm `migration.remoteSecretName`) | Flyway only, in the Helm pre-install/pre-upgrade Job (`migrate`) | owns `sc_ln_loan_lifecycle` and its tables; needs `CREATE` on the database |
| runtime `loan_lifecycle_app` (`DB_USERNAME`) | `<env>/loan-lifecycle-service/db-app` (Terraform output `app_db_secret_name`, Helm `externalSecret.remoteSecretName`) | the service pods | granted by V4 (Flyway placeholder `runtime_role`): `USAGE` on the schema; `SELECT, INSERT, UPDATE` on `loan`, `credit_reservation_generation`; `SELECT, INSERT, UPDATE, DELETE` on `loan_installment`, `outbox_event`; `SELECT, INSERT` on `repayment`, `repayment_allocation`, `inbox_message`. No DDL, no `TRUNCATE` (`LoanLifecycleServiceIT.theRuntimeRoleCannotRunDdlOrRewriteTheRepaymentLedger`) |

**DBA bootstrap, per environment, before the first deploy** (with the RDS master credential, Terraform
output `master_user_secret_arn`):

1. `CREATE ROLE loan_lifecycle_owner LOGIN PASSWORD '<generated>'; GRANT CREATE ON DATABASE db_ln_loan_lifecycle_<env> TO loan_lifecycle_owner;`
2. `CREATE ROLE loan_lifecycle_app LOGIN PASSWORD '<generated>';` (no membership in the owner, no `CREATE`)
3. Write both `{"username","password"}` documents to the two secrets Terraform created; set the Helm values
   `migration.remoteSecretName` and `externalSecret.remoteSecretName` to their names.
4. `helm upgrade --install` runs the migration Job first (it waits for External Secrets to sync the owner
   credential), then the pods start with `SPRING_FLYWAY_ENABLED=false`. Both hook resources are deleted once
   they succeed, which removes the owner credential's Kubernetes Secret.

If an environment was ever migrated with a single role, V4 recorded a no-op; grant the runtime role by hand
with the statements in `V4__grant_runtime_role_least_privilege.sql` before switching the pods to it.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<loan service conninfo>" <currency>`

- `<loan service conninfo>` connects as the schema owner (`db-migration` secret): the backfill stages
  and upserts rows the runtime role may not write.
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
| Customer cutover (`RUNBOOK-EXTRACT-cus-profile-kyc`, customer-profile-kyc-core 58f7369) through its step 5; its step 5 routes the monolith's credit writes to the customer service and switches this service to `CUSTOMER_CREDIT_ADAPTER=http` | loan step 2 | one credit ledger: otherwise the monolith and the customer service both move `used_credit` |
| Payments slice ready to cut over in the same window, publishing `evt.pay.payment.loan-payment-completed.v1` | loan step 2 | loan and payments writes move together; repayments must land in exactly one place |
| `evt.ln.loan.*`, `evt.ln.loan.dlq.v1`, `evt.pay.payment.loan-payment-completed.v1` in the asyncapi catalog and created on the cluster (catalog PR pending) | step 3 (consumer), step 4 (relay) | the service never creates topics |
| ConfigMap `rds-ca-bundle` (key `global-bundle.pem`) published in namespace `lending` by trust-manager (mesh repo `k8s/platform/cert-manager/bundle-rds-ca.yaml`); `DB_URL` = Terraform output `jdbc_url` (`sslmode=verify-full&sslrootcert=/etc/ssl/rds/global-bundle.pem`) | step 1 | the pods mount the bundle to verify Aurora's certificate; without it they do not start |
| Mesh team has applied the requests in "Requests to the mesh team" below (A: gateway route, B: Aurora and MSK egress, C: callee ALLOW rules, plus the README "Callers" ALLOW rules) | step 1 (B), step 2 (A, C) | namespace `lending` is default-deny and outbound traffic is `REGISTRY_ONLY`: without B the readiness check (`db`) fails and the pods never become ready |
| No INITIATED / PROCESSING monolith payments | step 2 final delta | in-flight repayments finish in the monolith |
| Monolith anti-corruption client sends `currency` (ISO 4217) on loan creation and payment, and reads `rateBasis` from loan responses, in the same window as step 2 (d) | step 2 (d) | `currency` is required and `rateBasis` replaces the monolith's flat-rate assumption (`api/openapi/loan-context.accepted-breaking.txt`); a client without them is refused (400) or misreads the rate. The accepted-breaking file stays until that client change merges in enterprise-loan-management-system |

### Requests to the mesh team

Raise these in `fintechbankx-platform-mesh-security-service-mesh` (owner: platform mesh squad) before step 1.
The chart ships no Istio objects; everything below is theirs to add.

**A. Gateway route (public paths).** Host: the API host of the environment. Route
`/api/v1/loans` and `/api/v1/loans/*` (methods `GET`, `POST`) to
`loan-lifecycle-service.lending.svc.cluster.local:8080`, with the platform's forwarded-header rules and
Keycloak `RequestAuthentication` (first-party web, mobile and staff tokens; `aud` must contain
`svc-ln-loan-lifecycle`). No other path of this service is public; `8081` (actuator) never is.

**B. Egress under `REGISTRY_ONLY` (ServiceEntry, `MESH_EXTERNAL`, `resolution: DNS`, exported to `lending`).**

| Destination | Hosts | Port / protocol | Why |
|---|---|---|---|
| Aurora PostgreSQL | cluster writer endpoint and reader endpoint (Terraform outputs; `jdbc_url` host and `reader_endpoint`) | 5432 `TLS` (the service does its own TLS, `sslmode=verify-full`) | JDBC; readiness group includes `db`, so without it the pods never become ready |
| Amazon MSK | the broker hostnames of the IAM listener (`KAFKA_BOOTSTRAP_SERVERS`) | 9098 `TLS` | outbox relay and repayment consumer (IAM auth via IRSA) |
| AWS STS (regional endpoint) | `sts.<region>.amazonaws.com` | 443 `TLS` | IRSA web-identity exchange used by the MSK IAM client |

The Flyway migration Job (Helm hook) runs without a sidecar by default (`migration.istioSidecar: false`),
so it is not subject to the egress policy but only reaches Aurora; tell the mesh team if the cluster runs
native sidecars, then turn the sidecar on.

**C. Callee ALLOW rules (this service as the caller, principal `cluster.local/ns/lending/sa/loan-lifecycle-service`).**

| Callee namespace / workload | Port | Paths | Why |
|---|---|---|---|
| `customer` / `customer-profile-kyc-service` | 8080 | `GET /api/v1/customers/*/credit`, `POST /api/v1/customers/*/credit/reserve`, `POST /api/v1/customers/*/credit/release` | credit position, reserve, release (`CUSTOMER_CREDIT_ADAPTER=http`) |
| `identity` / `keycloak` | 8080 | `POST /realms/fintechbankx/protocol/openid-connect/token` and the realm's `certs` (JWKS) | client-credentials token and JWT validation |
| `observability` / `otel-collector` | 4317, 4318 | OTLP | traces |

Inbound ALLOW rules for this service's own callers are listed in README "Callers".

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
| `outbox_parked_events` | > 0 |
| `outbox_oldest_pending_age_seconds` (once the relay is on) | > 300 s |
| Reconcile re-run against the frozen monolith snapshot | any `|f` line |

## 6. Acceptance checklist

- [x] Service builds and checks standalone (`ci/test` runs `./gradlew check` with PostgreSQL; ArchUnit rules; coverage)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order; ADR-021 decision 4: payload errors park at once, authorization/unclassified errors stop the relay without marking rows, retryable failures park only after 24 h of continuous failure (section 7)
- [x] Customer credit through the customer service API (no shared table); consumer contract test against customer-context.yaml
- [x] Delta backfill rehearsed with reconciliation in CI
- [ ] Monolith anti-corruption client and write-freeze flag (enterprise-loan-management-system)
- [ ] asyncapi catalog PR for `svc-ln-loan-lifecycle.yaml`; topics created (fintechbankx-platform-event-streaming-kafka)
- [x] Runtime role separated from the schema owner: V4 grants, Flyway as the owner in a Helm hook Job only, pods without the owner credential (`DatabaseMigrationIT`, `LoanLifecycleServiceIT`)
- [ ] DBA bootstrap of `loan_lifecycle_owner` and `loan_lifecycle_app` per environment, secrets filled
- [ ] Mesh team requests A (gateway route), B (Aurora, MSK, STS egress), C (callee ALLOW rules) applied, plus the ALLOW rules for the callers in README
- [ ] Production backfill and reconciliation report attached here

## 7. Parked outbox events

ADR-021 decision 4 (adr-runbooks #10, e6dd76a), the rule for every service's outbox relay:

- **Payload errors** (`RecordTooLargeException`, `SerializationException`, `InvalidTopicException`): the row
  can never be sent as it is. The relay parks it at once (`parked_at`, `park_reason = 'payload error: ...'`,
  `last_error`) and the batch continues.
- **Every other error** (Kafka retryable errors such as timeouts, not enough replicas, leader or network errors;
  the relay's own send timeout; SASL or topic authorization; a producer that cannot be built; anything
  unclassified) never parks a row, however long it lasts. The batch stops, **nothing is marked** (no attempt,
  no `last_error`), the relay backs off (1 s doubling to 5 min) and counts the failure in
  `outbox_send_failures_total{exception="<simple class name>"}`. There is no time ceiling: the row stays at the
  head until the cause is fixed or an operator parks it.

Note: a loan raises several events, so parking a row lets later events of the same loan go out before it.
Consumers must tolerate that until the row is replayed (they de-duplicate on `eventId` and carry
`aggregateVersion`).

Alerts: `outbox_oldest_pending_age_seconds{service="svc-ln-loan-lifecycle"}` for a stalled relay or an outage
(warn above 300 s, page above 1800 s); `rate(outbox_send_failures_total[5m])` by `exception` to see why
(authorization classes point at the IRSA role's MSK policy, topic existence or ACLs); `outbox_parked_events`
above zero; `outbox_pending_events` for the backlog.

Find the head of the queue and the parked rows:

```sql
SELECT event_id, created_seq, topic, attempts, last_error, parked_at, park_reason
FROM sc_ln_loan_lifecycle.outbox_event
WHERE published_at IS NULL
ORDER BY created_seq
LIMIT 20;
```

Operator park (only when the head row itself is the problem and the incident lead agrees; the relay never
does this for a non-payload error). The reason is mandatory and goes into the incident record too:

```sql
UPDATE sc_ln_loan_lifecycle.outbox_event
SET parked_at = now(), park_reason = 'operator: <incident id> <why>'
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NULL;
```

Replay, after fixing the cause:

```sql
UPDATE sc_ln_loan_lifecycle.outbox_event
SET parked_at = NULL, park_reason = NULL, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```
