#!/usr/bin/env bash
# Rehearses the monolith -> svc-ln-loan-lifecycle data split end to end on a
# scratch PostgreSQL: builds a monolith-shaped source, applies this service's
# Flyway migrations to a separate database, then
#   1. checks the backfill refuses to run without a currency and while a
#      monolith payment is in flight;
#   2. runs db/backfill/run-backfill.sh and checks the mapped values;
#   3. changes the monolith (a new repayment) and the service (a migrated loan
#      the service has since changed), re-runs, and checks the delta reached
#      monolith-owned rows only and the reconcile still passes.
#
#   scripts/migration/verify-backfill.sh <currency>      (CI passes it explicitly)
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <currency>   (ISO 4217, required)" >&2
  exit 2
fi
currency="$1"
root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_backfill_source"
dst_db="ln_backfill_target"
backfill="$root/db/backfill/run-backfill.sh"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA sc_ln_loan_lifecycle"
# Flyway placeholders, filled the way Flyway fills them: the runtime role is
# the connecting role here, so V4 takes its single-user path.
runtime_role="${PGUSER:-$(id -un)}"
for migration in $(ls "$root"/loan-infrastructure/src/main/resources/db/migration/V*.sql | sort -V); do
  sed "s/\${runtime_role}/$runtime_role/g" "$migration" \
    | PGOPTIONS="-c search_path=sc_ln_loan_lifecycle" psql_q -d "$dst_db" -f -
done

# A refusal case passes only when the refusal carries its own check's message, so
# a refusal by another check, or a run that goes through, fails the rehearsal.
expect_refusal_by() {
  local label="$1" pattern="$2" err; shift 2
  if err="$("$@" 2>&1 > /dev/null)"; then
    echo "FAIL $label: not refused, it ran" >&2
    exit 1
  fi
  if ! grep -q "$pattern" <<< "$err"; then
    echo "FAIL $label: refused, but not by the expected check: $err" >&2
    exit 1
  fi
  echo "ok   $label"
}

expect_pass() {
  local label="$1" out; shift
  if ! out="$("$@" 2>&1)"; then
    echo "FAIL $label: $out" >&2
    exit 1
  fi
  echo "ok   $label"
}

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

expect_refusal() {
  local label="$1"; shift
  if "$@" > /dev/null 2>&1; then
    echo "FAIL $label: the backfill ran" >&2
    exit 1
  fi
  echo "ok   $label"
}

L="sc_ln_loan_lifecycle"
A="11111111-1111-1111-1111-111111111111"
B="22222222-2222-2222-2222-222222222222"
C="33333333-3333-3333-3333-333333333333"
D="44444444-4444-4444-4444-444444444444"
E="55555555-5555-5555-5555-555555555555"
# Each guard case leaves exactly one loan holding a balance under the old schedule rule.
guard_refusal="BACKFILL REFUSED: 1 loan(s) hold a balance written under the old schedule rule (runbook section 2)"
inflight_refusal="BACKFILL REFUSED: [0-9][0-9]* monolith payment(s) are INITIATED or PROCESSING"

echo "--- preconditions"
expect_refusal "no currency, no run" "$backfill" "dbname=$src_db" "dbname=$dst_db"
psql_q -d "$src_db" -c "INSERT INTO payments (id, loan_id, payment_amount, payment_status) VALUES ('ffffffff-0000-0000-0000-000000000001', '$C', 1350.00, 'PROCESSING')"
expect_refusal_by "in-flight monolith payment, no run" "$inflight_refusal" "$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency"
check "nothing written while refused" "SELECT count(*) FROM $L.loan" "0"
psql_q -d "$src_db" -c "DELETE FROM payments WHERE id = 'ffffffff-0000-0000-0000-000000000001'"

echo "--- first run"
"$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency"

check "flat rate kept as stored, rate basis FLAT_TOTAL" \
  "SELECT rate_basis || ' ' || legacy_flat_rate::numeric(5,3) || ' ' || annual_interest_rate FROM $L.loan WHERE loan_id = '$B'" "FLAT_TOTAL 0.200 20.0000"
check "9-month flat rate annualised, 4 decimals" \
  "SELECT annual_interest_rate FROM $L.loan WHERE loan_id = '$D'" "26.6667"
check "currency is the run parameter" \
  "SELECT string_agg(DISTINCT currency, ',') FROM $L.loan" "$currency"
check "partly repaid loan: DISBURSED, principal not yet repaid (12000.00 less 3000.00 principal in 3 installments)" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM $L.loan WHERE loan_id = '$B'" "DISBURSED 9000.00"
check "late-term loan: 11 of 12 paid, 1000.00 principal still owed, not 0" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM $L.loan WHERE loan_id = '$E'" "DISBURSED 1000.00"
check "paid loan is FULLY_PAID with zero balance" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM $L.loan WHERE loan_id = '$A'" "FULLY_PAID 0.00"
check "installment split: principal + interest, remainder on the last" \
  "SELECT string_agg(installment_number || ':' || principal_amount::numeric(19,2) || '+' || interest_amount::numeric(19,2), ',' ORDER BY installment_number) FROM $L.loan_installment WHERE loan_id = '$D' AND installment_number IN (1, 9)" \
  "1:1111.11+222.22,9:1111.12+222.21"
check "installments numbered by due date" \
  "SELECT string_agg(installment_number::text || ':' || status, ',' ORDER BY installment_number) FROM $L.loan_installment WHERE loan_id = '$B' AND installment_number IN (1, 3, 4)" "1:PAID,3:PAID,4:PENDING"
check "completed payments become MONOLITH repayments; failed ones do not" \
  "SELECT count(*) || ' ' || sum(amount)::numeric(19,2) FROM $L.repayment WHERE source = 'MONOLITH'" "4 23395.00"
check "allocations carry penalty and discount per installment" \
  "SELECT string_agg(installment_number || ':' || amount::numeric(19,2) || '-' || discount::numeric(19,2) || '+' || penalty::numeric(19,2), ',' ORDER BY installment_number) FROM $L.repayment_allocation WHERE payment_id = 'bbbbbbbb-0000-0000-0000-000000000002'" \
  "2:1200.00-0.00+5.00,3:1200.00-10.00+0.00"
check "customer id carried as text, no customer table" \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$L' AND table_name LIKE 'customer%'" "0"

echo "--- monolith and service change, second run"
# Monolith: loan C's first installment is paid (new COMPLETED payment).
psql_q -d "$src_db" <<SQL
UPDATE loan_installments SET paid_amount = 1350.00, is_paid = TRUE, payment_date = '2026-04-09' WHERE id = md5('C1')::uuid::text;
INSERT INTO payments (id, loan_id, payment_amount, payment_date, payment_status, installments_paid)
     VALUES ('cccccccc-0000-0000-0000-000000000002', '$C', 1350.00, '2026-04-09', 'COMPLETED', 1);
INSERT INTO payment_installments VALUES ('cccccccc-0000-0000-0000-000000000002', md5('C1')::uuid::text);
UPDATE loans SET updated_at = '2026-04-09' WHERE id = '$C';
-- Monolith also changes loan B, which the service has changed meanwhile (below).
UPDATE loan_installments SET paid_amount = 1200.00, is_paid = TRUE, payment_date = '2026-04-09' WHERE id = md5('B4')::uuid::text;
SQL
# Service: loan B has been changed by the service since the first run (version > 0).
psql_q -d "$dst_db" -c "UPDATE $L.loan SET version = 1, updated_at = now() WHERE loan_id = '$B'"

"$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency"

check "delta reached the monolith-owned loan" \
  "SELECT outstanding_balance::numeric(19,2) || ' ' || source_updated_at::date FROM $L.loan WHERE loan_id = '$C'" "23000.00 2026-04-09"
check "delta reached its installment" \
  "SELECT status FROM $L.loan_installment WHERE loan_id = '$C' AND installment_number = 1" "PAID"
check "new monolith repayment loaded with its allocation" \
  "SELECT count(*) FROM $L.repayment_allocation WHERE payment_id = 'cccccccc-0000-0000-0000-000000000002'" "1"
check "loan changed by the service is not overwritten" \
  "SELECT status FROM $L.loan_installment WHERE loan_id = '$B' AND installment_number = 4" "PENDING"
check "nothing loaded twice" \
  "SELECT count(*) FROM $L.repayment WHERE source = 'MONOLITH'" "5"

echo "--- balances written under the old schedule rule"
# A loan created here holding the schedule's total still due (12,393.58 for 12,000.00 at 6%), not its principal.
psql_q -d "$dst_db" -c "INSERT INTO $L.loan (loan_id, customer_id, principal_amount, currency, annual_interest_rate, term_months,
                                             status, application_date, approval_date, disbursement_date, maturity_date,
                                             outstanding_balance, created_at, updated_at, rate_basis)
                        VALUES ('LOAN-OLD-SCHEDULE', '1', 12000.00, '$currency', 6.0000, 12, 'DISBURSED', '2026-06-01',
                                '2026-06-01', '2026-06-01', '2027-06-01', 12393.58, now(), now(), 'NOMINAL_ANNUAL')"
expect_refusal_by "created loan on the old schedule balance refuses the backfill" "$guard_refusal" \
  "$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency"
psql_q -d "$dst_db" -c "DELETE FROM $L.loan WHERE loan_id = 'LOAN-OLD-SCHEDULE'"
# A migrated loan the service changed, holding the schedule still due (10,800.00) instead of its principal (9,000.00).
psql_q -d "$dst_db" -c "UPDATE $L.loan SET outstanding_balance = 10800.00 WHERE loan_id = '$B'"
expect_refusal_by "changed migrated loan on the old schedule balance refuses the backfill" "$guard_refusal" \
  "$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency"
psql_q -d "$dst_db" -c "UPDATE $L.loan SET outstanding_balance = 9000.00 WHERE loan_id = '$B'"
"$backfill" "dbname=$src_db" "dbname=$dst_db" "$currency" > /dev/null
check "guard passes once the balance is the principal again" \
  "SELECT status || ' ' || outstanding_balance::numeric(19,2) FROM $L.loan WHERE loan_id = '$B'" "DISBURSED 9000.00"

echo "--- per-environment check (runbook section 2): the runbook command, three ways"
assert_sql="$root/db/backfill/00_assert_read_only_session.sql"
guard_sql="$root/db/backfill/00_guard_old_schedule_balance.sql"
expect_pass "PGOPTIONS read-only, plain conninfo: passes" \
  env PGOPTIONS='-c default_transaction_read_only=on' psql "dbname=$dst_db" -v ON_ERROR_STOP=1 -f "$assert_sql" -f "$guard_sql"
expect_refusal_by "PGOPTIONS, but options= in the conninfo replaces it: refused" "session is not read-only" \
  env PGOPTIONS='-c default_transaction_read_only=on' psql "dbname=$dst_db options='-c statement_timeout=60000'" -v ON_ERROR_STOP=1 -f "$assert_sql" -f "$guard_sql"
expect_refusal_by "neither PGOPTIONS nor options=: refused" "session is not read-only" \
  env -u PGOPTIONS psql "dbname=$dst_db" -v ON_ERROR_STOP=1 -f "$assert_sql" -f "$guard_sql"
expect_refusal_by "PGOPTIONS, but options= sets read-only off: refused" "session is not read-only" \
  env PGOPTIONS='-c default_transaction_read_only=on' psql "dbname=$dst_db options='-c default_transaction_read_only=off'" -v ON_ERROR_STOP=1 -f "$assert_sql" -f "$guard_sql"

echo "Backfill rehearsal passed."
