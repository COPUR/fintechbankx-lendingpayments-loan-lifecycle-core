-- One Kafka topic per aggregate (ADR-019 sections 1, 3 and 8; owner decision
-- 2026-10-08): every event of the Loan aggregate goes to evt.ln.loan.v1,
-- named by its eventType record header. The relay computes the topic and no
-- longer reads outbox_event.topic; rows written before this migration stored
-- the old per-event topic, so they are pointed at the aggregate topic here and
-- the operator queries of the runbook show where a row goes. Nothing was ever
-- relayed to the per-event topics (the relay has been off everywhere).
--
-- fapi_interaction_id: the x-fapi-interaction-id of the API request that
-- raised the event. The relay sends that header only when it is set, i.e.
-- when the flow started at the loan API; rows written before this migration
-- and rows raised by the repayment consumer or the sweep leave it NULL.
--
-- DDL and DML on an existing table; V4's table grant covers the runtime role.

ALTER TABLE outbox_event ADD COLUMN fapi_interaction_id VARCHAR(128);

UPDATE outbox_event SET topic = 'evt.ln.loan.v1' WHERE topic <> 'evt.ln.loan.v1';

COMMENT ON COLUMN outbox_event.topic IS
    'Aggregate topic evt.ln.loan.v1 (V11, one topic per aggregate); the relay computes it and does not read this column.';
COMMENT ON COLUMN outbox_event.fapi_interaction_id IS
    'x-fapi-interaction-id of the API request that raised the event; NULL when the flow did not start at the API.';
