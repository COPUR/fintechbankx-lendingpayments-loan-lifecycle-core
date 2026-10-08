-- A compensation (release of a reservation whose disbursement rolled back) is
-- recorded before the release is sent: generation moves to n+1 and
-- pending_compensation = n, in one transaction of its own. The release is
-- sent under the key of generation n; once it is accepted,
-- pending_compensation is cleared. A pending compensation is re-sent under the
-- same key before the next reservation (CustomerProfileHttpAdapter), so a
-- retry never replays the key of a released reservation.

ALTER TABLE credit_reservation_generation ADD COLUMN pending_compensation INTEGER;

COMMENT ON COLUMN credit_reservation_generation.pending_compensation IS
    'Generation whose compensating release was started but not yet confirmed; NULL when none.';
