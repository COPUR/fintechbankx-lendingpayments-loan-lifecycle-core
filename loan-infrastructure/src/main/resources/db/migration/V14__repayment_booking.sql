-- Repayment bookings (ADR-030, plan 02 step 1b). An applied repayment writes its
-- ledger booking in its own transaction; the booking sweep posts it later under
-- loan-repayment:<paymentId>. Amounts and account ids are fixed when the repayment
-- is applied, so a later change of configuration does not change a booking already written.
CREATE TABLE repayment_booking (
    reference                  VARCHAR(128)   PRIMARY KEY,
    loan_id                    VARCHAR(64)    NOT NULL REFERENCES loan (loan_id),
    currency                   VARCHAR(3)     NOT NULL,
    principal_amount           NUMERIC(19, 4) NOT NULL,
    interest_amount            NUMERIC(19, 4) NOT NULL,
    receivable_account_id      VARCHAR(64)    NOT NULL,
    clearing_account_id        VARCHAR(64)    NOT NULL,
    interest_income_account_id VARCHAR(64),
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    booked_at                  TIMESTAMPTZ,
    journal_entry_id           VARCHAR(64),
    parked                     BOOLEAN        NOT NULL DEFAULT FALSE,
    attempts                   INTEGER        NOT NULL DEFAULT 0,
    last_error                 VARCHAR(64),
    CONSTRAINT ck_repayment_booking_amounts
        CHECK (principal_amount >= 0 AND interest_amount >= 0 AND principal_amount + interest_amount > 0),
    CONSTRAINT ck_repayment_booking_interest_account
        CHECK (interest_amount = 0 OR interest_income_account_id IS NOT NULL),
    CONSTRAINT ck_repayment_booking_booked
        CHECK (booked_at IS NULL OR journal_entry_id IS NOT NULL)
);

-- The sweep reads only what is neither booked nor parked.
CREATE INDEX idx_repayment_booking_pending
    ON repayment_booking (created_at, reference)
    WHERE booked_at IS NULL AND NOT parked;

-- V4's least-privilege grants cover only the tables it names; this table is granted here.
DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RETURN;
    END IF;

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE repayment_booking TO %I', runtime_role);
END
$$;
