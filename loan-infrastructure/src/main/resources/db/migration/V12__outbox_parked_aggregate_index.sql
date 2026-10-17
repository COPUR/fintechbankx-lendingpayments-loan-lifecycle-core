-- One topic per aggregate (V11, ADR-019): a loan's sagas rely on its events
-- arriving in order. A parked row (a payload park by the relay, or an
-- operator park, ADR-021 decision 4) now holds back every later row of the
-- same loan until it is replayed (runbook section 7); rows of other loans
-- continue. The relay's pending query (SpringDataOutboxRepository
-- findUnpublishedBatch) checks per row whether its loan has a parked row;
-- this partial index serves that check. Same rule as bulk-orchestration V9.
--
-- DDL only; an index needs no grant (V4's table grant covers the runtime role).

CREATE INDEX ix_outbox_parked_aggregate ON outbox_event (aggregate_id) WHERE parked_at IS NOT NULL;

COMMENT ON INDEX ix_outbox_parked_aggregate IS
    'Loans with a parked outbox row; their later rows wait for the replay (relay pending query).';
