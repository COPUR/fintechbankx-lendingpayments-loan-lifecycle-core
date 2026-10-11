-- Customer release-by-reference (provider contract pending): a release names
-- the reservation by its reference. 422 RELEASE_EXCEEDS_RESERVATION means this
-- service asked to release more than the customer service holds for the loan,
-- which is a bug signal: the pending compensation is kept, the code is stored
-- here, nothing is re-sent, and an operator resolves it
-- (loan_credit_reservations_operator{reason="release_exceeds_reservation"}).
-- 422 RESERVATION_NOT_FOUND needs no column: nothing was reserved, the intent
-- is done (counted in loan_credit_releases_unmatched_total).
--
-- DDL only; V4's table grant (SELECT, INSERT, UPDATE) covers the runtime role.

ALTER TABLE credit_reservation_generation ADD COLUMN release_refused_code VARCHAR(64);

COMMENT ON COLUMN credit_reservation_generation.release_refused_code IS
    'Customer error code that refused the pending compensation (operator action); NULL otherwise.';
