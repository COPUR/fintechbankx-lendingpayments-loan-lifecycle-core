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
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, alarms; platform `microservice-base` module) |
| Runtime config | `loan-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox backlog gauge `loan.outbox.pending`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server with Keycloak realm roles and method security; non-root, read-only root filesystem, all capabilities dropped; DB credential and the service's Keycloak client secret (`<env>/loan-lifecycle-service/oidc-client`) from Secrets Manager via External Secrets, never in config; calls to the customer service use this service's own client-credentials token (client `svc-ln-loan-lifecycle`, realm role `service`), never the end user's token; Kafka on MSK with IAM client auth (`aws` profile) and a produce-only IAM policy on `evt.ln.loan.*` topics; KMS-encrypted storage, snapshots, logs and secrets with rotation; TLS enforced (`rds.force_ssl`); IRSA least-privilege policy; DB reachable only from the workload security group; no customer PII stored or published | `SecurityConfiguration`, `deployment.yaml`, `externalsecret.yaml`, `main.tf`, `LoanEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ with reader failover (`aurora_instance_count` >= 2), 35-day PITR in prod, deletion protection and final snapshot; pods spread across zones, PDB, zero-unavailable rolling updates, graceful shutdown; transactional outbox (no lost events), ordered single-relay publishing, idempotent Kafka producer; optimistic locking on the loan; timeouts on the customer-service call | `main.tf`, `deployment.yaml`, `pdb.yaml`, `OutboxRelay`, `JpaLoanRepositoryAdapter`, `CustomerCreditClientConfiguration` |
| Performance efficiency | Stateless pods scaled by HPA on CPU and memory; Aurora Serverless v2 scales ACUs; virtual threads for request handling; partial indexes for overdue scans and the outbox queue; JDBC batching | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_loan_tables.sql`, `V2__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); outbox rows purged after 7 days; log retention 30 days outside prod | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak (HPA scale-down policy, Serverless ACUs); layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- Kafka topics for `evt.ln.loan.*.v1` are not yet created on the platform cluster; the IAM produce policy is applied once `msk_cluster_arn` is set.
- The Keycloak client `svc-ln-loan-lifecycle` with the `service` realm role must exist in the platform realm (identity repo).
- The application DB role (`loan_lifecycle_app`) is created by a DBA bootstrap step, not by Terraform, so Terraform never holds the password.
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
