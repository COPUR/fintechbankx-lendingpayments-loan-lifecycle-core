-- Flyway runs as the schema owner (DB_MIGRATION_USERNAME), which owns
-- sc_ln_loan_lifecycle and every table in it. The service connects as the
-- runtime role (${runtime_role}, from DB_USERNAME) and gets only the DML the
-- code issues:
--   loan                           SELECT, INSERT, UPDATE           (aggregate; LoanRepository.delete has no caller)
--   loan_installment               SELECT, INSERT, UPDATE, DELETE   (schedule rows; orphan removal in LoanPersistenceMapper)
--   repayment                      SELECT, INSERT                   (ledger, insert-only)
--   repayment_allocation           SELECT, INSERT                   (ledger, insert-only)
--   inbox_message                  SELECT, INSERT                   (consumer de-duplication)
--   credit_reservation_generation  SELECT, INSERT, UPDATE           (upsert in JdbcReservationGenerations)
--   outbox_event                   SELECT, INSERT, UPDATE, DELETE   (relay marks, parks and purges rows)
-- plus USAGE on the schema. Not being the owner, it can neither CREATE, ALTER,
-- DROP nor TRUNCATE, and it cannot read flyway_schema_history.
-- Every later migration that adds a table grants the runtime role explicitly.
--
-- Local single-user runs (no DB_MIGRATION_USERNAME) migrate as the runtime
-- role itself; then there is nothing to separate and this migration only says
-- so, because revoking the owner's own privileges would break later migrations.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the schema owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC', current_schema());

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE loan TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE loan_installment TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE repayment, repayment_allocation, inbox_message TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE credit_reservation_generation TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_event TO %I', runtime_role);
END
$$;
