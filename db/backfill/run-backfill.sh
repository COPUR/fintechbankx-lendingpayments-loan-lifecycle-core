#!/usr/bin/env bash
# Copies loan data from the monolith database into svc-ln-loan-lifecycle's own
# database and reconciles the two. Re-runnable and delta-safe: each run
# upserts monolith-owned rows the service has not changed (see
# 02_transform_into_loan_service.sql), so it is run once ahead of cutover and
# once more inside the write freeze (runbook step 2).
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <loan-service-conninfo> <currency>
#
# <currency> is REQUIRED (ISO 4217): the monolith stores no currency and its
# code falls back to USD, so the currency of the book being migrated must be
# stated explicitly by whoever runs the migration.
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <monolith-conninfo> <loan-service-conninfo> <currency>" >&2
  echo "       <currency> is required (ISO 4217); there is no default" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
currency="$3"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! [[ "$currency" =~ ^[A-Z]{3}$ ]]; then
  echo "currency must be an ISO 4217 code, got '$currency'" >&2
  exit 2
fi

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Checking for in-flight monolith payments..."
in_flight="$(psql -X -At "$source_db" -c "SELECT count(*) FROM payments WHERE payment_status IN ('INITIATED', 'PROCESSING')")"
if [ "$in_flight" != "0" ]; then
  echo "BACKFILL REFUSED: $in_flight monolith payment(s) are INITIATED or PROCESSING." >&2
  echo "They finish in the monolith and are not migrated; wait for them, then re-run (runbook step 2)." >&2
  exit 1
fi

echo "Exporting from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT id, customer_id, loan_amount, number_of_installments, interest_rate, create_date, is_paid, created_at, updated_at FROM loans ORDER BY id) TO '$work/loans.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT id, loan_id, amount, paid_amount, due_date, payment_date, is_paid FROM loan_installments ORDER BY loan_id, due_date, id) TO '$work/loan_installments.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT id, loan_id, payment_amount, payment_date, installments_paid, total_discount, total_penalty, is_loan_fully_paid FROM payments WHERE payment_status = 'COMPLETED' ORDER BY id) TO '$work/payments.csv' WITH (FORMAT csv, HEADER true)
\copy (SELECT pi.payment_id, pi.installment_id FROM payment_installments pi JOIN payments p ON p.id = pi.payment_id WHERE p.payment_status = 'COMPLETED' ORDER BY 1, 2) TO '$work/payment_installments.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SQL

echo "Staging and transforming into sc_ln_loan_lifecycle (currency $currency)..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.loans FROM '$work/loans.csv' WITH (FORMAT csv, HEADER true)
\copy backfill_stage.loan_installments FROM '$work/loan_installments.csv' WITH (FORMAT csv, HEADER true)
\copy backfill_stage.payments FROM '$work/payments.csv' WITH (FORMAT csv, HEADER true)
\copy backfill_stage.payment_installments FROM '$work/payment_installments.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -v currency="$currency" -f "$here/02_transform_into_loan_service.sql"

echo "Reconciling monolith-owned rows (check|monolith|loan service|ok)..."
report="$(psql -X -At -v ON_ERROR_STOP=1 "$target_db" -f "$here/03_reconcile.sql")"
echo "$report"
if echo "$report" | grep -q '|f$'; then
  echo "RECONCILIATION FAILED: see the lines ending in |f" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
