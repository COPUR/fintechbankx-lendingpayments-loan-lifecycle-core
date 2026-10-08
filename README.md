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
DB_URL=jdbc:postgresql://localhost:5432/<db> DB_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<password> \
CUSTOMER_CREDIT_ADAPTER=in-memory CUSTOMER_CREDIT_LEDGER_CURRENCY=AED \
OUTBOX_RELAY_ENABLED=false LOAN_REPAYMENT_CONSUMER_ENABLED=false \
./gradlew :loan-bootstrap:bootRun
```

Kafka is only contacted by the outbox relay and the repayment consumer, both off by default; events
still land in `outbox_event`. The in-memory credit adapter knows the monolith's stub customers
`CUST-12345678`, `CUST-87654321`, `CUST-11111111`. Calls need a JWT from the configured issuer
(`OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`) whose `aud` contains `svc-ln-loan-lifecycle`; customers are
identified by the token's `customer_id` claim.

## Events

```yaml
published_events:   # api/asyncapi/svc-ln-loan-lifecycle.yaml, key = loanId, via the transactional outbox
  - evt.ln.loan.created.v1        # Lending.Loan.Created.v1
  - evt.ln.loan.approved.v1       # Lending.Loan.Approved.v1
  - evt.ln.loan.rejected.v1       # Lending.Loan.Rejected.v1
  - evt.ln.loan.disbursed.v1      # Lending.Loan.Disbursed.v1
  - evt.ln.loan.cancelled.v1      # Lending.Loan.Cancelled.v1
  - evt.ln.loan.payment-made.v1   # Lending.Loan.PaymentMade.v1
  - evt.ln.loan.fully-paid.v1     # Lending.Loan.FullyPaid.v1
  - evt.ln.loan.dlq.v1            # this service's DLQ (records the repayment consumer gave up on)
consumed_events:
  - topic: evt.pay.payment.loan-payment-completed.v1   # Payments.Payment.LoanPaymentCompleted.v1
    group: cg.svc-ln-loan-lifecycle.loan-repayment-allocation.v1
    enabled_by: LOAN_REPAYMENT_CONSUMER_ENABLED (default false)
```

The topics are not yet in the platform asyncapi catalog (catalog PR pending); the relay stays off
(`OUTBOX_RELAY_ENABLED=false`) until they are.

## Callers (mesh ALLOW rules)

The chart ships no PeerAuthentication or AuthorizationPolicy (platform contract). The mesh repo needs
ALLOW rules on namespace `lending` for these principals:

| Principal | Port | Why |
|---|---|---|
| `cluster.local/ns/istio-ingress/sa/<ingress gateway SA>` | 8080 | web, mobile and staff clients through the gateway |
| `cluster.local/ns/payments/sa/payment-initiation-settlement-service` | 8080 | reads a loan before taking a repayment (if the payments service calls this API) |
| `cluster.local/ns/observability/sa/<prometheus SA>` | 8081 | Prometheus scrape of `/actuator/prometheus` |

The chart's NetworkPolicy admits 8080 from `istio-ingress` and `payments`, 8081 from `observability`.

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
