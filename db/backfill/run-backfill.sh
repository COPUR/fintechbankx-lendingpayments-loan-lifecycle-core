#!/usr/bin/env bash
# Copies loan data from the monolith database into svc-ln-loan-lifecycle's own
# database and reconciles the two. Re-runnable.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <loan-service-conninfo> [currency]
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <monolith-conninfo> <loan-service-conninfo> [currency]" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
currency="${3:-AED}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! [[ "$currency" =~ ^[A-Z]{3}$ ]]; then
  echo "currency must be an ISO 4217 code, got '$currency'" >&2
  exit 2
fi

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Exporting loans from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT id, customer_id, loan_amount, number_of_installments, interest_rate, create_date, is_paid, created_at, updated_at FROM loans ORDER BY id) TO '$work/loans.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT id, loan_id, amount, paid_amount, due_date, payment_date, is_paid FROM loan_installments ORDER BY loan_id, due_date, id) TO '$work/loan_installments.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SQL

echo "Staging and transforming into sc_ln_loan_lifecycle..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.loans FROM '$work/loans.csv' WITH (FORMAT csv, HEADER true)
\copy backfill_stage.loan_installments FROM '$work/loan_installments.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -v currency="$currency" -f "$here/02_transform_into_loan_service.sql"

echo "Reconciling..."
source_figures="$(psql -X -At "$source_db" -c "
  SELECT count(*), coalesce(sum(loan_amount), 0)::numeric(19,2), count(*) FILTER (WHERE is_paid) FROM loans
  UNION ALL
  SELECT count(*), coalesce(sum(amount), 0)::numeric(19,2), coalesce(sum(paid_amount), 0)::numeric(19,2) FROM loan_installments")"
target_figures="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql" | sed -n '1,2p')"
invariant_breaks="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql" | sed -n '3,$p')"

echo "monolith     rows|amount|paid: $(echo "$source_figures" | tr '\n' ' ')"
echo "loan service rows|amount|paid: $(echo "$target_figures" | tr '\n' ' ')"
if [ "$source_figures" != "$target_figures" ]; then
  echo "RECONCILIATION FAILED: totals differ" >&2
  exit 1
fi
if [ -n "$invariant_breaks" ]; then
  echo "RECONCILIATION FAILED: loans breaking per-loan invariants:" >&2
  echo "$invariant_breaks" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
