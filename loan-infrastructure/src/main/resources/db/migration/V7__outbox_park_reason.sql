-- ADR-021 decision 4 (adr-runbooks #10 e6dd76a): the relay parks a row only
-- for a payload error (record too large, serialization, invalid topic). Every
-- other send failure stops the batch without marking the row, however long it
-- lasts, so the 24 h first_failed_at ceiling of V5 is removed. An operator may
-- still park a row by hand, and must record why (runbook section 7).

ALTER TABLE outbox_event DROP COLUMN first_failed_at;

ALTER TABLE outbox_event ADD COLUMN park_reason VARCHAR(512);

COMMENT ON COLUMN outbox_event.park_reason IS
    'Why the row is parked: "payload error: <exception>" from the relay, or the operator''s recorded reason. Cleared with parked_at on a re-drive.';
