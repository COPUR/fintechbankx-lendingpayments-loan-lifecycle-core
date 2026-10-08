-- Review fixes for the loan data split (PR #14):
--   * interest basis: monolith loans carry a flat total rate, not an annual one;
--   * every installment carries its principal / interest split, so a
--     repayment can be allocated interest first;
--   * repayment history (moved here from the payment service: the loan
--     context owns how a payment was applied to the schedule);
--   * inbox for evt.pay.payment.loan-payment-completed.v1 (de-duplication on eventId);
--   * credit reservation generations (compensated reservations);
--   * outbox rows that keep failing are parked instead of blocking the relay.

-- --- loan: rate basis and backfill bookkeeping --------------------------------

ALTER TABLE loan
    ADD COLUMN rate_basis        VARCHAR(16)    NOT NULL DEFAULT 'NOMINAL_ANNUAL',
    ADD COLUMN legacy_flat_rate  NUMERIC(19, 6),
    ADD COLUMN source_updated_at TIMESTAMP,
    ADD COLUMN backfilled_at     TIMESTAMPTZ,
    ADD CONSTRAINT ck_loan_rate_basis CHECK (rate_basis IN ('NOMINAL_ANNUAL', 'FLAT_TOTAL')),
    ADD CONSTRAINT ck_loan_flat_rate_only_for_flat CHECK (
        (rate_basis = 'FLAT_TOTAL' AND legacy_flat_rate IS NOT NULL)
        OR (rate_basis = 'NOMINAL_ANNUAL' AND legacy_flat_rate IS NULL));

COMMENT ON COLUMN loan.annual_interest_rate IS
    'Percent per year (6.5 = 6.5%). NOMINAL_ANNUAL loans: nominal rate compounded monthly on the declining balance. '
    'FLAT_TOTAL loans (migrated): the flat total rate annualised for display only, legacy_flat_rate * 100 * 12 / term_months; '
    'their installments are the monolith''s and are never recalculated.';
COMMENT ON COLUMN loan.rate_basis IS 'NOMINAL_ANNUAL for loans created here; FLAT_TOTAL for loans migrated from the monolith.';
COMMENT ON COLUMN loan.legacy_flat_rate IS
    'Monolith loans.interest_rate as stored: a fraction of the principal charged once over the whole term (0.200 = 20%).';
COMMENT ON COLUMN loan.outstanding_balance IS
    'Total still due on the schedule: unpaid principal plus unpaid scheduled interest, for every loan.';
COMMENT ON COLUMN loan.source_updated_at IS 'Monolith loans.updated_at of the last backfill run that wrote this row.';
COMMENT ON COLUMN loan.backfilled_at IS
    'When the backfill last wrote this row. A row whose version is still 0 has not been changed by the service since.';

-- --- installments: principal / interest split ----------------------------------

ALTER TABLE loan_installment
    ADD COLUMN principal_amount NUMERIC(19, 4),
    ADD COLUMN interest_amount  NUMERIC(19, 4);

UPDATE loan_installment SET principal_amount = amount, interest_amount = 0 WHERE principal_amount IS NULL;

ALTER TABLE loan_installment
    ALTER COLUMN principal_amount SET NOT NULL,
    ALTER COLUMN interest_amount SET NOT NULL,
    ADD CONSTRAINT ck_installment_components CHECK (
        principal_amount >= 0 AND interest_amount >= 0 AND principal_amount + interest_amount = amount);

-- --- repayment history -----------------------------------------------------------

CREATE TABLE repayment (
    payment_id        VARCHAR(64)    PRIMARY KEY,
    loan_id           VARCHAR(64)    NOT NULL REFERENCES loan (loan_id),
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    installments_paid INTEGER        NOT NULL DEFAULT 0,
    discount_total    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    penalty_total     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    loan_fully_paid   BOOLEAN        NOT NULL,
    applied_at        TIMESTAMPTZ    NOT NULL,
    source            VARCHAR(16)    NOT NULL,
    request_scope     VARCHAR(128),
    idempotency_key   VARCHAR(128),
    legacy_payment_id VARCHAR(64),

    CONSTRAINT ck_repayment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_repayment_source CHECK (source IN ('API', 'PAYMENT_EVENT', 'MONOLITH')),
    CONSTRAINT ck_repayment_key_scope CHECK ((request_scope IS NULL) = (idempotency_key IS NULL)),
    CONSTRAINT uq_repayment_request_idempotency UNIQUE (request_scope, idempotency_key),
    CONSTRAINT uq_repayment_legacy_payment_id UNIQUE (legacy_payment_id)
);

CREATE INDEX ix_repayment_loan ON repayment (loan_id, applied_at);

COMMENT ON TABLE repayment IS
    'Repayments applied to a loan. payment_id is the payment service''s id (or this service''s for API repayments); '
    'monolith payments keep their id.';
COMMENT ON COLUMN repayment.discount_total IS 'Monolith payments.total_discount (early-payment discount); 0 for repayments applied here.';
COMMENT ON COLUMN repayment.penalty_total IS 'Monolith payments.total_penalty (late penalty); 0 for repayments applied here.';

CREATE TABLE repayment_allocation (
    payment_id         VARCHAR(64)    NOT NULL REFERENCES repayment (payment_id) ON DELETE CASCADE,
    installment_id     UUID           NOT NULL REFERENCES loan_installment (installment_id),
    loan_id            VARCHAR(64)    NOT NULL REFERENCES loan (loan_id),
    installment_number INTEGER        NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    principal          NUMERIC(19, 4) NOT NULL,
    interest           NUMERIC(19, 4) NOT NULL,
    discount           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    penalty            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency           VARCHAR(3)     NOT NULL,
    allocated_at       TIMESTAMPTZ    NOT NULL,

    PRIMARY KEY (payment_id, installment_id),
    CONSTRAINT ck_allocation_split CHECK (principal >= 0 AND interest >= 0 AND principal + interest = amount)
);

CREATE INDEX ix_allocation_installment ON repayment_allocation (installment_id);

COMMENT ON TABLE repayment_allocation IS
    'How each repayment was applied to the schedule, one row per installment touched. Replaces the payment '
    'service''s legacy_payment_installment and the monolith''s payment_installments link table.';

-- --- inbox for consumed events ---------------------------------------------------

CREATE TABLE inbox_message (
    event_id       UUID         NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    topic          VARCHAR(249) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX ix_inbox_processed_at ON inbox_message (processed_at);

COMMENT ON TABLE inbox_message IS
    'Events this service consumed and applied, written in the same transaction as the side effect; a redelivered '
    'eventId is skipped. Messages that keep failing go to this service''s DLQ (evt.ln.loan.dlq.v1).';

-- --- credit reservation generations ---------------------------------------------

CREATE TABLE credit_reservation_generation (
    loan_id    VARCHAR(64) PRIMARY KEY REFERENCES loan (loan_id),
    generation INTEGER     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_generation_positive CHECK (generation > 0)
);

COMMENT ON TABLE credit_reservation_generation IS
    'Bumped when a credit reservation was released again because the disbursement transaction rolled back, so the '
    'next attempt uses a new idempotency key (ln:{loanId}:reserve:g{n}) instead of replaying the compensated one.';

-- --- outbox: park poison rows ----------------------------------------------------

ALTER TABLE outbox_event
    ADD COLUMN parked_at   TIMESTAMPTZ,
    ADD COLUMN traceparent VARCHAR(55);

COMMENT ON COLUMN outbox_event.traceparent IS
    'W3C traceparent of the request that raised the event; sent as a Kafka header so the trace crosses the broker.';

DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE parked_at IS NOT NULL;

COMMENT ON COLUMN outbox_event.parked_at IS
    'Set after loan.outbox.relay.max-attempts failed sends; the relay skips the row. Re-drive by clearing parked_at.';
