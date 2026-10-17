-- Retryable send failures (broker, DNS or mesh-egress outages, timeouts) no
-- longer count toward parking; the max-attempts cap of V3 is removed. A row is
-- parked on a retryable failure only after it has been failing continuously
-- for longer than loan.outbox.relay.retryable-park-after (default 24 h),
-- measured from first_failed_at. Non-retryable failures still park at once.
-- Same column as risk-decisioning (V6) and compliance-evidence (V8).

ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.first_failed_at IS 'First failed send of the row; reset to NULL with parked_at on a manual replay.';
