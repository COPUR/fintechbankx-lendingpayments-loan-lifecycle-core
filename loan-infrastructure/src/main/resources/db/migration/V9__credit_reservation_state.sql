-- Review 5460235552: record each credit reservation before it is sent, so a
-- loan that is cancelled, rejected or abandoned after a failed disbursement
-- can release the credit it still holds, and a recovery sweep can re-drive
-- what a crash left behind (CustomerProfileHttpAdapter, CreditReservationSweep).
--
-- reservation_state of the row's current generation:
--   RESERVING    written in its own transaction before the reserve is sent;
--                whether the customer service applied it is unknown
--   RESERVED     the customer service accepted the reserve
--   UNCONFIRMED  was RESERVING longer than the sweep's grace period on a loan
--                that was never disbursed; an operator decides (the customer
--                service's release does not check that a reservation exists,
--                so a blind release would free other loans' credit)
--   USED         set inside the disbursement transaction
--   NULL         no reservation outstanding (refused, compensated, or a row
--                written before V9)
--
-- The first reservation of a loan is now recorded too, at generation 0, so
-- the V3 check (generation > 0) becomes generation >= 0.
--
-- DDL only; the runtime role keeps the SELECT, INSERT, UPDATE on this table
-- that V4 granted (the new column is covered by the table grant).

ALTER TABLE credit_reservation_generation DROP CONSTRAINT ck_generation_positive;

ALTER TABLE credit_reservation_generation
    ADD CONSTRAINT ck_generation_non_negative CHECK (generation >= 0);

ALTER TABLE credit_reservation_generation ADD COLUMN reservation_state VARCHAR(16);

ALTER TABLE credit_reservation_generation
    ADD CONSTRAINT ck_reservation_state CHECK (reservation_state IN ('RESERVING', 'RESERVED', 'UNCONFIRMED', 'USED'));

CREATE INDEX ix_credit_reservation_unresolved ON credit_reservation_generation (updated_at)
    WHERE pending_compensation IS NOT NULL OR reservation_state IN ('RESERVING', 'RESERVED', 'UNCONFIRMED');

COMMENT ON COLUMN credit_reservation_generation.reservation_state IS
    'RESERVING (sent, outcome unknown), RESERVED (accepted), UNCONFIRMED (operator), USED (disbursed); NULL when none is outstanding.';
