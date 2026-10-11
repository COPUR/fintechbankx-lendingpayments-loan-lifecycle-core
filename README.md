# fintechbankx-lendingpayments-loan-lifecycle-core

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-ln-loan-lifecycle** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Lending & Payments Tribe |
| Squad | Loan Lifecycle Squad |
| Repo Kümesi (Capability) | lending |
| Service ID | svc-ln-loan-lifecycle |
| Bounded Context | loan_lifecycle |
| Wave | 3 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- loan_lifecycle bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Run, test and deploy

| What | Command / path |
|---|---|
| Full gate (unit, integration, ArchUnit, coverage) | `./gradlew check`. Integration tests use `TEST_DB_URL` (or Docker); without either they are skipped locally and **fail** when `CI=true`. |
| Run locally without Kafka or the customer service | see "Local boot" below |
| Database migrations | `loan-infrastructure/src/main/resources/db/migration` (schema `sc_ln_loan_lifecycle`) |
| HTTP contract | `api/openapi/loan-context.yaml` (breaking changes need `loan-context.accepted-breaking.txt`, see `scripts/ci/oasdiff-breaking.sh`) |
| Event contract | `api/asyncapi/svc-ln-loan-lifecycle.yaml` (AsyncAPI 3.0) |
| Container image | `docker build -t loan-lifecycle-service .` |
| Kubernetes | `deploy/helm/loan-lifecycle-service` (required values listed in `values.yaml`) |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-ln-loan-lifecycle](docs/migration/RUNBOOK-EXTRACT-ln-loan-lifecycle.md), rehearsal `scripts/migration/verify-backfill.sh <currency>` |
| Monolith-to-service regression mapping | [REGRESSION_MAPPING](docs/migration/REGRESSION_MAPPING.md) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

Module layout (FinTechBankX service guardrails, ADR-028): `loan-domain` (aggregate, events,
`domain.port.in` use cases, `domain.port.out` ports) <- `loan-application` (use-case implementations,
DTOs) <- `loan-infrastructure` (`web`, `persistence`, `outbox`, `messaging`, `external`, `config`)
<- `loan-bootstrap` (Spring Boot app). `HexagonalArchitectureTest` enforces the four ArchUnit rules on
`check`.

### Local boot

No Kafka, no customer service, no Keycloak token needed to start:

```
SPRING_PROFILES_ACTIVE=local \
DB_URL=jdbc:postgresql://localhost:5432/<db> DB_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<password> \
CUSTOMER_CREDIT_ADAPTER=in-memory CUSTOMER_CREDIT_LEDGER_CURRENCY=AED \
OUTBOX_RELAY_ENABLED=false LOAN_REPAYMENT_CONSUMER_ENABLED=false \
./gradlew :loan-bootstrap:bootRun
```

Local runs keep a plain local URL, which is why the `local` profile is needed: the service refuses to start
unless every datasource URL a pool can use (`spring.datasource.url` = `DB_URL`, `spring.datasource.hikari.jdbc-url`,
`spring.flyway.url`), read as PgJDBC reads it (case-sensitive keys, exactly one `sslmode`, no `sslfactory`,
`sslfactoryarg`, `sslhostnameverifier`, `sslpasswordcallback` or `service`), has `sslmode=verify-full`, and the
effective Kafka `security.protocol` of the producer (and of the consumer when one exists), bound through
`KafkaProperties`, is `SASL_SSL` (profile `kafka-msk`) or `SSL` (profile `kafka-strimzi`, mutual TLS);
`PLAINTEXT`, `SASL_PLAINTEXT` and unset are refused (`fintechbankx.tls.enforce`, true by default;
`TlsEnforcement` names the offending setting and its sslmode or protocol, never the URL). The migration Job's
`migrate` context imports the same check for its `DB_URL`. Only the `local` profile and the bootstrap test
resources switch it off; the chart never does and refuses every route to the `local` profile ("Chart guard" below).
Deployed pods use the Terraform output `jdbc_url`
(`...?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`): the chart mounts the platform
ConfigMap `rds-ca-bundle` (key `global-bundle.pem`) read-only at `/etc/fintechbankx/rds-ca` and refuses a `DB_URL`
that does not verify the Aurora certificate against it.

Kafka is only contacted by the outbox relay and the repayment consumer, both off by default; events
still land in `outbox_event`. The in-memory credit adapter knows the monolith's stub customers
`CUST-12345678`, `CUST-87654321`, `CUST-11111111`. Calls need a JWT from the configured issuer
(`OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`) whose `aud` contains `svc-ln-loan-lifecycle`; customers are
identified by the token's `customer_id` claim.

## Events

```yaml
published_events:   # api/asyncapi/svc-ln-loan-lifecycle.yaml, via the transactional outbox
  - topic: evt.ln.loan.v1        # one topic per aggregate (ADR-019); key = loanId; eventType header
    event_types:
      - Lending.Loan.Created.v1
      - Lending.Loan.Approved.v1
      - Lending.Loan.Rejected.v1
      - Lending.Loan.Disbursed.v1
      - Lending.Loan.Cancelled.v1
      - Lending.Loan.PaymentMade.v1
      - Lending.Loan.FullyPaid.v1
  - topic: evt.ln.loan.dlq.v1    # this service's DLQ (records the repayment consumer gave up on)
consumed_events:
  - topic: evt.pay.payment.v1    # payment aggregate topic; every other event type is skipped
    event_types:
      - Payments.Payment.LoanPaymentCompleted.v1
    group: cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1
    enabled_by: LOAN_REPAYMENT_CONSUMER_ENABLED (default false)
```

Every record carries the UTF-8 headers `eventType`, `eventId` and `correlationId` (equal to the envelope's),
`x-fapi-interaction-id` when the flow started at the loan API, and `traceparent` when the request was traced.
The repayment consumer reads the `eventType` header first: any type other than
`Payments.Payment.LoanPaymentCompleted.v1` is skipped (offset committed, never failed, never dead-lettered).

The topics are not yet in the platform asyncapi catalog (catalog PR pending); the relay stays off
(`OUTBOX_RELAY_ENABLED=false`) until they are.

History: until 2026-10-08 the contract used one topic per event type; the owner decided on one topic per
aggregate (ADR-019 section 8). Nothing was ever published to the per-event topics, and migration V11 points
outbox rows written before it at `evt.ln.loan.v1`.

### AsyncAPI gate

`ci/test` validates `api/asyncapi/*.yaml` with `@asyncapi/cli@2.13.0` and runs the catalog's breaking-change
check against `origin/main` (`ASYNCAPI_DIR=api/asyncapi`, ADR-019 section 5). The check's scripts and the
shared envelope are copies of the asyncapi catalog (`fintechbankx-governance-api-contracts-asyncapi-catalog`)
at commit `44837cc`, unchanged; the step "AsyncAPI gate scripts match the catalog copy" fails when a copy's
sha256 differs:

| Copy | sha256 |
|---|---|
| `scripts/ci/asyncapi-breaking.mjs` | `de255737fe6b54ffbadce8e030e18eec48d0137e0abd43c790fe201a10015eb1` |
| `scripts/ci/asyncapi-breaking.sh` | `5b39d588673c5f7ab55fcfa548fffd70b96ab9b11ea82bd2b61468dadb430c77` |
| `scripts/ci/lib/asyncapi-model.mjs` | `212df6ca092e1ed5519664d7848c9de5fdb5d137f34faa3d31a25e3fe29b3847` |

`api/asyncapi/common/event-envelope.yaml` is the catalog's `asyncapi/common/event-envelope.yaml` at the same
commit. Waivers go in `api/asyncapi/<spec-name>.accepted-breaking.txt` and need the API owner's review
(CODEOWNERS); the spec is not on `origin/main` yet, so the gate skips it as a new file and none is needed.

### Chart guard

`deploy/helm/loan-lifecycle-service/templates/_fbx_helpers.tpl` is a byte-identical copy, without a header, of the
shared chart's helpers: source repository `COPUR/fintechbankx-platform-delivery-iac-cicd-templates`, path
`charts/fintechbankx-service/templates/_helpers.tpl`, commit `6b6c317` (its datasource/TLS guard is `fbx.guard`, with
`fbx.validateEnvSources`, `fbx.validateDatabaseTls`, `fbx.validateKafkaTls`, `fbx.validateKafkaTlsValue`,
`fbx.validateSecretNames`, `fbx.validateKeyNames`, `fbx.validateDatabaseCa`, `fbx.validateJdbcUrl`,
`fbx.validateJvmOptions`, `fbx.datasourceOverrideName`, `fbx.canonicalName`, `fbx.propertyName` and
`fbx.kafkaProfile`; its other `fbx.*` helpers are not included anywhere, and no name collides with this chart's
`loan.*`). The `deploy/helm` job runs `scripts/ci/verify-vendored-guard.sh deploy/helm/loan-lifecycle-service <sha256>`
(a byte copy of the script at the same commit); it fails when the sha256 differs, when another template file redefines
an `fbx.*` template, or when `deployment.yaml` or `migration-job.yaml` does not run the guard before it writes anything:

| Copy | sha256 |
|---|---|
| `deploy/helm/loan-lifecycle-service/templates/_fbx_helpers.tpl` | `8ba2e4a11ead019c25bf0a01e4fe4bbabb6fef0e1980e5fa828ccb9f5673f8ba` |
| `scripts/ci/verify-vendored-guard.sh` | `430a88e1a6939d4a40dc32f0c6d201775f64e717e5eeb39b0348c8f8c0435eac` |

Do not edit either: copy the reference again and update the commit and the sum here and in
`.github/workflows/deployability.yml` (the step "Vendored platform guard" names the source repository, path and commit
beside the pinned digest). `templates/_helpers.tpl` `loan.guardValues` (called at the top of
`deployment.yaml` and `migration-job.yaml`) hands `fbx.guard` an adapter dict mapping this chart's routes onto the
shared chart's value names: `config`, `extraEnv`/`envFrom`/`extraEnvFrom` (none rendered; mapped so an addition is
refused), `javaToolOptions` (none), `databaseCa` (`enabled` always true, `mountPath`, `key` and `configMapName` from the
values the templates mount; the guard pins them to `/etc/fintechbankx/rds-ca`, `global-bundle.pem` and `rds-ca-bundle`,
which are this chart's defaults, and refuses a null `configMapName`), `kafka.runtime` (from `kafka.profile`:
`kafka-msk` is `msk`, `kafka-strimzi` is `strimzi`; `SPRING_PROFILES_ACTIVE` is rendered through `fbx.kafkaProfile`)
and the fixed ExternalSecret keys with their `remoteSecretName` values. Rules this chart keeps because `fbx.guard` has
none: `kafka.profile` must be one of the two Kafka profiles (never `local`), and no `spring.kafka.properties.*` key or
extraEnv name (the guard refuses the `ssl.*` names, `security.protocol` and the endpoint identification value only).
The chart's former "no `kafka` in a JVM option" rule is gone: `fbx.validateJvmOptions` refuses `kafka` and `mongodb`.
Every key and value the templates interpolate is quoted (`int` for numbers), and the job checks that a config value
with a newline adds no key and no container arg. The job's refusal loop lists every spelling the loan #14 review probed
(`values.yaml` `config` comment) and a `refuse` probe, with its expected message, for each rule the guard adds,
including the platform's database CA, Kafka client TLS, DocumentDB and property-reading cases through this chart's
value names.

## Callers (mesh ALLOW rules)

The chart ships no PeerAuthentication or AuthorizationPolicy (platform contract). The mesh repo needs
ALLOW rules on namespace `lending` for these principals:

| Principal | Port | Why |
|---|---|---|
| `cluster.local/ns/istio-ingress/sa/<ingress gateway SA>` | 8080 | web, mobile and staff clients through the gateway |
| `cluster.local/ns/payments/sa/payment-initiation-settlement-service` | 8080 | reads a loan before taking a repayment (if the payments service calls this API) |
| `cluster.local/ns/observability/sa/<prometheus SA>` | 8081 | Prometheus scrape of `/actuator/prometheus` |

NetworkPolicy belongs to the mesh repo, so the chart's own is off by default (`networkPolicy.enabled: false`); opted in, it admits 8080 from `istio-ingress` and `payments`, and 15020 and 8081 from `observability`, never a CIDR. Metrics are scraped only by the observability repo's PodMonitor (pod label `fintechbankx.io/service-id`, Istio merged metrics); the chart ships no ServiceMonitor. Every object carries `fintechbankx.io/squad: lending`.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: \
- Backlog: \

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->
