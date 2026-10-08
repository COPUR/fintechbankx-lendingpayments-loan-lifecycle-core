# Deployment and AWS Well-Architected mapping

How `svc-ln-loan-lifecycle` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
API gateway / Istio ingress ──▶ loan-lifecycle-service pods (EKS, 3..12, HPA)
                                   │  ├─ HTTP ─▶ customer-profile-kyc-service (credit)
                                   │  └─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                                   └─ outbox relay ─▶ Kafka evt.ln.loan.*.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/loan-lifecycle-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS key tagged `fintechbankx.io/secrets=true`, Secrets Manager secret synced by External Secrets, MSK-only IRSA role, alarms; platform `microservice-base` module) |
| Runtime config | `loan-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox gauges `outbox_pending_events`, `outbox_oldest_pending_age_seconds` and relay counters `outbox_send_failures_total{exception}`, `outbox_parked_events_total{exception}` (Kafka guide 5f7d546), every meter tagged `service` and `squad`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server with Keycloak realm roles and method security; non-root, read-only root filesystem, all capabilities dropped; DB credential and the service's Keycloak client secret (`<env>/loan-lifecycle-service/oidc-client`) from Secrets Manager via External Secrets, never in config; calls to the customer service use this service's own client-credentials token (client `svc-ln-loan-lifecycle`, realm role `service`), never the end user's token; tokens must name `svc-ln-loan-lifecycle` in `aud`; customers are identified by the `customer_id` claim and see only their own loans (403 otherwise); Kafka on MSK with IAM client auth (`kafka-msk` profile; `kafka-strimzi` for in-cluster Strimzi) and an IAM policy limited to producing `evt.ln.loan.*` (including the DLQ), reading `evt.pay.payment.loan-payment-completed.v1` and groups `cg.svc-ln-loan-lifecycle.*`; KMS-encrypted storage, snapshots, logs and secrets with rotation; TLS enforced (`rds.force_ssl`) and the server certificate verified (`sslmode=verify-full`, `sslrootcert=/etc/ssl/rds/global-bundle.pem` from the platform ConfigMap `rds-ca-bundle`, mounted read-only; the chart refuses any other `DB_URL`); the pods' IRSA role has no Secrets Manager or KMS grants (External Secrets reads the secret by the KMS key tag); NetworkPolicy is the mesh repo's (the chart's own is off by default and opt-in only, namespaces only, never a CIDR); DB reachable only from the workload security group; no customer PII stored or published | `SecurityConfiguration`, `deployment.yaml`, `externalsecret.yaml`, `main.tf`, `LoanEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ with reader failover (`aurora_instance_count` >= 2), 35-day PITR in prod, deletion protection and final snapshot; pods spread across zones, PDB, zero-unavailable rolling updates, graceful shutdown; transactional outbox (no lost events), ordered single-relay publishing, idempotent Kafka producer (`delivery.timeout.ms` 30 s >= `linger.ms` + `request.timeout.ms` 20 s, relay waits 35 s), per ADR-021 decision 4 only payload errors park a row (with `park_reason`); every other send failure stops the batch without marking anything, backs off and counts in `outbox_send_failures_total{exception}`, with no time ceiling, so an outage only delays events (operator park with a recorded reason, runbook section 7); repayment consumer with inbox de-duplication, 3 retries and DLQ `evt.ln.loan.dlq.v1`; no database transaction held open across customer-service calls; credit reservation cancelled if the disbursement cannot be stored (the compensation intent is stored before the release and an unconfirmed release is re-sent under its key before the next reservation, V6); optimistic locking on the loan; timeouts on the customer-service call | `main.tf`, `deployment.yaml`, `pdb.yaml`, `OutboxRelay`, `JpaLoanRepositoryAdapter`, `CustomerCreditClientConfiguration` |
| Performance efficiency | Stateless pods scaled by HPA on CPU only (a JVM does not return memory, so a memory target only ratchets up; memory request = limit); Aurora Serverless v2 scales ACUs; virtual threads for request handling; partial indexes for overdue scans and the outbox queue; JDBC batching | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_loan_tables.sql`, `V2__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); outbox rows purged after 7 days; log retention 30 days outside prod | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak (HPA scale-down policy, Serverless ACUs); layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- Kafka topics for `evt.ln.loan.*.v1` are not yet created on the platform cluster; the IAM produce policy is applied once `msk_cluster_arn` is set.
- The Keycloak client `svc-ln-loan-lifecycle` with the `service` realm role must exist in the platform realm (identity repo).
- The DB roles (schema owner `loan_lifecycle_owner`, runtime `loan_lifecycle_app`) are created by a DBA bootstrap step, not by Terraform, so Terraform never holds the passwords; Terraform creates their secrets (`<env>/loan-lifecycle-service/db-migration`, `.../db-app`). Flyway runs as the owner only in the Helm pre-install/pre-upgrade Job; the pods hold the runtime role and run with `SPRING_FLYWAY_ENABLED=false`.
- The migration Job runs without an Istio sidecar by default (a classic sidecar keeps a Job from completing); switch `migration.istioSidecar` on once the cluster runs native sidecars.
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
