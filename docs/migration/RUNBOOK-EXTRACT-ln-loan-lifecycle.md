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
| Customer cutover (`RUNBOOK-EXTRACT-cus-profile-kyc`, customer-profile-kyc-core 8794365) through its step 5; its step 5 routes the monolith's credit writes to the customer service and switches this service to `CUSTOMER_CREDIT_ADAPTER=http` | loan step 2 | one credit ledger: otherwise the monolith and the customer service both move `used_credit` |
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

The Flyway migration Job (Helm pre-install/pre-upgrade hook) needs egress to Aurora on 5432 as well. It runs
without a sidecar by default (`migration.istioSidecar: false`), so the Istio egress policy does not apply to it,
but the mesh repo's NetworkPolicies do: they grant Aurora egress by `app.kubernetes.io/name` only, so the Job
pod carries `app.kubernetes.io/name=loan-lifecycle-service` like the service pods, and
`app.kubernetes.io/component=db-migration` (service pods: `service`, platform convention cicd-templates 335a345) keeps it out of the Service, PDB and
Deployment selectors. Ask the mesh team to keep the 5432 egress rule keyed on that label (not on the
component) and tell them if the cluster runs native sidecars, then turn the sidecar on.

**Selector labels must land before any deploy.** A Deployment's `spec.selector` is immutable, so the
`app.kubernetes.io/component=service` selector (and the Job pod's `db-migration`) must be in the chart before the
first install of this release in any environment. No release exists yet; if one did, changing the selector would
need the Deployment deleted and recreated (a full outage of this service), not a `helm upgrade`. The Job pod gets
no exemption from the mesh or admission policies: it is selected by the 5432 egress rule through its name label,
and CI (`scripts/ci/helm-selector-check.py`) fails if any Service, PDB, NetworkPolicy, topology spread or
Deployment selector would select it, or if it carries `fintechbankx.io/service-id`.

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
| Platform alert `OutboxEventsParked` (`outbox_parked_events_total`, 15 minutes) | fires for this service |
| Platform alert `OutboxRelayStalled` (`outbox_oldest_pending_age_seconds` above 900 s for 5 minutes, once the relay is on) | fires for this service |
| Reconcile re-run against the frozen monolith snapshot | any `|f` line |

## 6. Acceptance checklist

- [x] Service builds and checks standalone (`ci/test` runs `./gradlew check` with PostgreSQL; ArchUnit rules; coverage)
- [x] Own schema and migrations; Hibernate validates entities against them at startup
- [x] Events written through a transactional outbox, relayed in order; ADR-021 decision 4 (adr-runbooks #10 e6dd76a): payload errors park the row at once and the batch continues; every other error (retryable, authorization, unclassified) parks nothing however long it lasts: the batch stops, nothing is marked, the relay backs off, and only an operator parks a row, with a recorded reason (section 7)
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
  `last_error`), counts it in `outbox_parked_events_total{exception="<simple class name>"}` (platform alert
  `OutboxEventsParked`) and the batch continues.
- **Every other error** (Kafka retryable errors such as timeouts, not enough replicas, leader or network errors;
  the relay's own send timeout; SASL or topic authorization; a producer that cannot be built; anything
  unclassified) never parks a row, however long it lasts. The batch stops, **nothing is marked** (no attempt,
  no `last_error`), the relay backs off (1 s doubling to 5 min) and counts the failure in
  `outbox_send_failures_total{exception="<simple class name>"}`. There is no time ceiling: the row stays at the
  head until the cause is fixed or an operator parks it.

Note: a loan raises several events, so parking a row lets later events of the same loan go out before it.
Consumers must tolerate that until the row is replayed (they de-duplicate on `eventId` and carry
`aggregateVersion`).

Alerts. The squad acts on the platform alerts (observability `prometheus/rules/kafka-outbox.rules.yml`,
PR #11 head eca7aa0, routed by the `squad` label); this chart ships no outbox alert rule:
- `OutboxRelayStalled` (critical): `outbox_oldest_pending_age_seconds{service_id="svc-ln-loan-lifecycle"}` above 900 s for
  5 minutes, a stalled relay or a Kafka/MSK outage. `rate(outbox_send_failures_total[5m])` by `exception` says why
  (authorization classes point at the IRSA role's MSK policy, topic existence or ACLs); `OutboxSendFailures` (warning)
  fires on those failures.
- `OutboxEventsParked` (warning): any increase of `outbox_parked_events_total` over 15 minutes; its `exception` label
  is a payload error class or `OperatorPark`.

`outbox_parked_rows` shows the rows parked now and `outbox_pending_events` the backlog.

Find the head of the queue and the parked rows:

```sql
SELECT event_id, created_seq, topic, attempts, last_error, parked_at, park_reason
FROM sc_ln_loan_lifecycle.outbox_event
WHERE published_at IS NULL
ORDER BY created_seq
LIMIT 20;
```

Operator park (only when the head row itself is the problem and the incident lead agrees; the relay never
does this for a non-payload error). The reason is mandatory and goes into the incident record too. The
relay counts the park once on its next run, `outbox_parked_events_total{exception="OperatorPark"}` (column
`park_counted`, V8):

```sql
UPDATE sc_ln_loan_lifecycle.outbox_event
SET parked_at = now(), park_reason = 'operator: <incident id> <why>'
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NULL;
```

Replay, after fixing the cause:

```sql
UPDATE sc_ln_loan_lifecycle.outbox_event
SET parked_at = NULL, park_reason = NULL, park_counted = false, attempts = 0, last_error = NULL
WHERE event_id = '<event id>' AND published_at IS NULL AND parked_at IS NOT NULL;
```

## 8. Credit reservations needing an operator

Status: **Proposed** (merged from loan-review-r4: V9 `reservation_state`, V10 `release_refused_code`,
`CreditReservationSweep`). Applies only with `CUSTOMER_CREDIT_ADAPTER=http`.

Every credit reservation is recorded in `sc_ln_loan_lifecycle.credit_reservation_generation` (one row per loan)
before it is sent to `svc-cus-profile-kyc`. The customer service records each movement in its own table
(`credit_movement`: customer, idempotency key, `RESERVE` or `RELEASE`, amount, currency, reference). This service
never reads that table: every check below is a request to the customer squad (owner of
`fintechbankx-customer-profile-kyc-core`), quoting the customer id, the loan id (the movement's `reference`) and
the idempotency keys. Keys: reserve `<loanId>:reserve` at generation 0, `<loanId>:reserve:g<n>` at generation n;
compensating release `<reserve key>:compensation`; repayment release `<loanId>:release`.

### Recovery sweep

`CreditReservationSweep` runs on start-up and then `CREDIT_RESERVATION_SWEEP_INTERVAL` (default `PT1M`) after the
previous run ended, on one replica at a time (PostgreSQL advisory lock). It looks only at loans never disbursed
(`CREATED`, `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `CANCELLED`) whose row has not changed for
`CREDIT_RESERVATION_SWEEP_GRACE` (default `PT10M`), and:

- re-sends a pending compensation under its compensation key;
- releases a reservation recorded `RESERVED` under its compensation key (on a still `APPROVED` loan a later
  disbursement then fails because its reservation is gone);
- marks a reservation still `RESERVING` (sent, never answered) as `UNCONFIRMED` and leaves it for an operator;
- skips any row with `release_refused_code` set.

The chart sets `CREDIT_RESERVATION_SWEEP_ENABLED` (default `"true"`), `CREDIT_RESERVATION_SWEEP_INTERVAL` and
`CREDIT_RESERVATION_SWEEP_GRACE` in `values.yaml` `config`. Switching the sweep off stops recovery: compensations
are then only re-sent by the next reservation or cancellation of the same loan.

### Metrics

| Micrometer name (Prometheus name) | Type | Meaning |
|---|---|---|
| `loan.credit.reservations.pending` (`loan_credit_reservations_pending`) | gauge | never-disbursed loans with a pending compensation or an outstanding reservation (`RESERVING`, `RESERVED`, `UNCONFIRMED`). Short-lived during a disbursement; a value that stays above zero for longer than the grace period plus one interval means the sweep cannot clear it |
| `loan.credit.reservations.operator{reason="unconfirmed"}` (`loan_credit_reservations_operator{reason="unconfirmed"}`) | gauge | rows in `UNCONFIRMED`; any value above zero needs the procedure below |
| `loan.credit.reservations.operator{reason="release_exceeds_reservation"}` (same, Prometheus) | gauge | rows whose compensating release the customer service refused with `RELEASE_EXCEEDS_RESERVATION`; a bug signal, any value above zero needs the procedure below |
| `loan.credit.releases.unmatched` (`loan_credit_releases_unmatched_total`) | counter | compensating releases answered `RESERVATION_NOT_FOUND`: nothing was held, the intent is closed automatically. A rising rate means reserves are being recorded that the customer service never applied |

This service ships no alert rules. Observability (`fintechbankx-platform-observability-sre-operations`) owns any
alert on these metrics; the squad's ask is: either `operator` gauge above 0 (warning), and
`loan_credit_reservations_pending` above 0 for longer than the grace period plus one interval (warning).

Find the rows:

```sql
SELECT r.loan_id, l.customer_id, l.status, l.principal_amount, l.currency, r.generation,
       r.reservation_state, r.pending_compensation, r.release_refused_code, r.updated_at
FROM sc_ln_loan_lifecycle.credit_reservation_generation r
JOIN sc_ln_loan_lifecycle.loan l ON l.loan_id = r.loan_id
WHERE r.reservation_state = 'UNCONFIRMED' OR r.release_refused_code IS NOT NULL
ORDER BY r.updated_at;
```

Every change below runs under an incident id, with the incident lead's agreement, and the customer squad's
answer is attached to the incident record.

### UNCONFIRMED: a reserve sent but never answered

The reserve under key `<loanId>:reserve[:g<n>]` (n = `generation`) was sent and no answer was recorded, so this
service does not know whether the customer service applied it. **Never release it blind.** The customer
service's release does not check that a reservation exists for the reference (customer #13
`CreditProfile.releaseCredit`), and the customer's `used_credit` includes credit migrated from the monolith that
has no movement or reference behind it (customer-profile-kyc-core `db/backfill/02_transform_into_customer_service.sql`
copies `used_credit` without movements). A release for a reserve that was never applied would therefore free
credit held by the customer's other loans or by untracked migrated credit, and nothing would show it.

1. Ask the customer squad: for customer `<customer_id>`, is there a `credit_movement` with idempotency key
   `<reserve key>`, and what are its type, amount, currency and reference? List every movement with reference
   `<loan_id>` too.
2. **The reserve was applied** (a `RESERVE` under that key, amount and currency equal to the loan's
   `principal_amount` and `currency`, reference the loan id, and no `RELEASE` under `<reserve key>:compensation`):
   record it as accepted. On an `APPROVED` loan a disbursement can then use it; otherwise the sweep releases it
   under its compensation key after the grace period, and `loan_credit_reservations_pending` returns to 0.

   ```sql
   UPDATE sc_ln_loan_lifecycle.credit_reservation_generation
   SET reservation_state = 'RESERVED', updated_at = now()
   WHERE loan_id = '<loan id>' AND generation = <n> AND reservation_state = 'UNCONFIRMED'
     AND pending_compensation IS NULL;
   ```

3. **The reserve was never applied** (no movement under that key): nothing is held; close the row the way a
   refused reserve is closed. The generation stays, so a later reserve of an `APPROVED` loan reuses the key,
   which the customer service has never seen.

   ```sql
   UPDATE sc_ln_loan_lifecycle.credit_reservation_generation
   SET reservation_state = NULL, updated_at = now()
   WHERE loan_id = '<loan id>' AND generation = <n> AND reservation_state = 'UNCONFIRMED';
   ```

4. **Anything else** (amount, currency or reference differ, or a movement under the key exists for another
   customer): change nothing, escalate to the loan squad's engineers and the customer squad.

A retry of the disbursement of an `APPROVED` loan also resolves an `UNCONFIRMED` row: it re-sends the reserve
under the same key, which the customer service either replays (already applied) or applies now, and the row
becomes `RESERVED`. That is safe; a release is not.

### RELEASE_EXCEEDS_RESERVATION: a compensating release refused

Requires the customer service's release by reference (provider contract pending). The row has
`pending_compensation = <n>` and `release_refused_code = 'RELEASE_EXCEEDS_RESERVATION'`: the release under
`<reserve key of n>:compensation` asked for more than the customer service holds for reference `<loan_id>`.
The release is never re-sent by this service, the sweep skips the row, and a new reservation for the loan
fails until the row is closed.

1. Ask the customer squad for every movement of customer `<customer_id>` with reference `<loan_id>`, and the
   amount it still holds for that reference (reserves minus releases).
2. **Nothing is held** for the reference (it was already released, for example under an earlier key): close the
   intent.

   ```sql
   UPDATE sc_ln_loan_lifecycle.credit_reservation_generation
   SET pending_compensation = NULL, release_refused_code = NULL, updated_at = now()
   WHERE loan_id = '<loan id>' AND pending_compensation = <n> AND release_refused_code = 'RELEASE_EXCEEDS_RESERVATION';
   ```

3. **Part of the amount is held** (more than 0, less than the loan's `principal_amount`): the customer squad
   releases exactly the held amount for reference `<loan_id>` as a staff movement under a new key,
   `<reserve key of n>:compensation:operator`, recorded in the incident; then close the intent with the SQL of
   step 2. Raise a bug for the loan squad: the amounts disagree.
4. **The full amount or more is held**: the refusal contradicts the customer's own records. Change nothing and
   escalate to the customer squad as a provider defect.

Never clear `release_refused_code` while leaving `pending_compensation` set: that re-arms the same refused
release, which the sweep and the next reservation re-send.
