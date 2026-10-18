-- Step 0 of the per-environment check (docs/migration runbook, section 2), run before
-- 00_guard_old_schedule_balance.sql with PGOPTIONS='-c default_transaction_read_only=on'.
-- Stops the check unless this session is read-only. An options= keyword in the conninfo
-- replaces PGOPTIONS, so this assertion is what catches a writable session in that case.
-- A session default, not a privilege boundary. Changes nothing.

\set ON_ERROR_STOP on

DO $$
BEGIN
    IF current_setting('transaction_read_only') <> 'on' THEN
        RAISE EXCEPTION 'session is not read-only: run the check as the service role with PGOPTIONS=-c default_transaction_read_only=on, and without an options= keyword in the conninfo';
    END IF;
END
$$;
