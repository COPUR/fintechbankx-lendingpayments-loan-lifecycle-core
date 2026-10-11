-- svc-ln-loan-lifecycle owns these tables. Schema: sc_ln_loan_lifecycle
-- (Flyway runs with that schema as default, so names are unqualified).
-- Split from the monolith's V2__Create_loans_table.sql and
-- V3__Create_loan_installments_table.sql. Differences on purpose:
--   * no foreign key to customers: the customer lives in svc-cus-profile-kyc,
--     this service keeps only the customer id;
--   * no triggers: is_paid / updated_at are maintained by the Loan aggregate;
--   * status and currency are stored explicitly instead of an is_paid flag.

CREATE TABLE loan (
    loan_id              VARCHAR(64)    PRIMARY KEY,
    customer_id          VARCHAR(64)    NOT NULL,
    principal_amount     NUMERIC(19, 4) NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    annual_interest_rate NUMERIC(7, 4)  NOT NULL,
    term_months          INTEGER        NOT NULL,
    status               VARCHAR(32)    NOT NULL,
    application_date     DATE           NOT NULL,
    approval_date        DATE,
    disbursement_date    DATE,
    maturity_date        DATE,
    outstanding_balance  NUMERIC(19, 4) NOT NULL,
    created_at           TIMESTAMP      NOT NULL,
    updated_at           TIMESTAMP      NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    legacy_loan_id       VARCHAR(64),

    CONSTRAINT ck_loan_principal_positive CHECK (principal_amount > 0),
    CONSTRAINT ck_loan_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_loan_rate_range CHECK (annual_interest_rate >= 0 AND annual_interest_rate <= 100),
    CONSTRAINT ck_loan_term_range CHECK (term_months BETWEEN 1 AND 600),
    CONSTRAINT ck_loan_outstanding_non_negative CHECK (outstanding_balance >= 0),
    CONSTRAINT ck_loan_status CHECK (status IN (
        'CREATED', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DISBURSED', 'FULLY_PAID',
        'DEFAULTED', 'REJECTED', 'CANCELLED', 'RESTRUCTURED', 'WRITTEN_OFF')),
    CONSTRAINT uq_loan_legacy_loan_id UNIQUE (legacy_loan_id)
);

CREATE INDEX ix_loan_customer_id ON loan (customer_id, created_at DESC);
CREATE INDEX ix_loan_status ON loan (status);
-- Overdue scan only looks at loans that can still take repayments.
CREATE INDEX ix_loan_repaying_maturity ON loan (maturity_date)
    WHERE status IN ('ACTIVE', 'DISBURSED', 'RESTRUCTURED');

COMMENT ON TABLE loan IS 'Loan aggregate root (svc-ln-loan-lifecycle). Customer data is referenced by id only.';
COMMENT ON COLUMN loan.annual_interest_rate IS 'Annual rate in percent (6.5 = 6.5%). The monolith stored a fraction (0.065).';
COMMENT ON COLUMN loan.legacy_loan_id IS 'Monolith loans.id for rows backfilled from enterprise-loan-management-system; NULL for loans created here.';

CREATE TABLE loan_installment (
    installment_id     UUID           PRIMARY KEY,
    loan_id            VARCHAR(64)    NOT NULL REFERENCES loan (loan_id) ON DELETE CASCADE,
    installment_number INTEGER        NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    paid_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency           VARCHAR(3)     NOT NULL,
    due_date           DATE           NOT NULL,
    paid_at            TIMESTAMP,
    status             VARCHAR(32)    NOT NULL,

    CONSTRAINT uq_loan_installment_number UNIQUE (loan_id, installment_number),
    CONSTRAINT ck_installment_number_positive CHECK (installment_number > 0),
    CONSTRAINT ck_installment_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_installment_paid_range CHECK (paid_amount >= 0 AND paid_amount <= amount),
    CONSTRAINT ck_installment_status CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED')),
    CONSTRAINT ck_installment_paid_consistency CHECK (
        (status = 'PAID' AND paid_at IS NOT NULL AND paid_amount = amount)
        OR (status <> 'PAID'))
);

CREATE INDEX ix_installment_open_due_date ON loan_installment (due_date) WHERE status <> 'PAID';

COMMENT ON TABLE loan_installment IS 'Repayment schedule of a loan; one row per installment number.';
