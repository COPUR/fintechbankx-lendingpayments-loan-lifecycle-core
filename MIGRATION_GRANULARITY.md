# Migration Granularity Notes

- Repository: `fintechbankx-lending-loan-lifecycle-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- dir: `loan-context` -> `.`
- dir: `loan-service` -> `legacy/loan-service`
- file: `api/openapi/loan-context.yaml` -> `api/openapi/loan-context.yaml`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.
- 2026-10-07: seed turned into a runnable service. The service owns `sc_ln_loan_lifecycle` with its own Flyway migrations; the monolith's loan rows move with `db/backfill/run-backfill.sh` (see `docs/migration/RUNBOOK-EXTRACT-ln-loan-lifecycle.md`). `legacy/loan-service` remains read-only reference.

