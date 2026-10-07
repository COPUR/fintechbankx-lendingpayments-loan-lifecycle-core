#!/usr/bin/env bash
# Rehearses the monolith -> svc-ln-loan-lifecycle data split end to end on a
# scratch PostgreSQL: builds a monolith-shaped source, applies this service's
# Flyway migrations to a separate database, runs db/backfill/run-backfill.sh
# twice (the second run proves it is idempotent) and checks the mapped values.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_backfill_source"
dst_db="ln_backfill_target"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA sc_ln_loan_lifecycle"
for migration in "$root"/loan-infrastructure/src/main/resources/db/migration/V*.sql; do
  PGOPTIONS="-c search_path=sc_ln_loan_lifecycle" psql_q -d "$dst_db" -f "$migration"
done

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" AED
done

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

check "rate converted to percent" \
  "SELECT annual_interest_rate::numeric(7,2) FROM sc_ln_loan_lifecycle.loan WHERE loan_id = '22222222-2222-2222-2222-222222222222'" "20.00"
check "partially repaid loan stays DISBURSED with unpaid balance" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM sc_ln_loan_lifecycle.loan WHERE loan_id = '22222222-2222-2222-2222-222222222222'" "DISBURSED 10800.00"
check "paid loan is FULLY_PAID with zero balance" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM sc_ln_loan_lifecycle.loan WHERE loan_id = '11111111-1111-1111-1111-111111111111'" "FULLY_PAID 0.00"
check "installments numbered by due date" \
  "SELECT string_agg(installment_number::text || ':' || status, ',' ORDER BY installment_number) FROM sc_ln_loan_lifecycle.loan_installment WHERE loan_id = '22222222-2222-2222-2222-222222222222' AND installment_number IN (1, 3, 4)" "1:PAID,3:PAID,4:PENDING"
check "customer id carried as text, no customer table" \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sc_ln_loan_lifecycle' AND table_name LIKE 'customer%'" "0"

echo "Backfill rehearsal passed."
