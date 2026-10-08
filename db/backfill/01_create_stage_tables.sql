-- Step 1 of the loan data split: staging tables in the LOAN SERVICE database
-- that receive a CSV snapshot of the monolith's loans, loan_installments,
-- payments and payment_installments (enterprise-loan-management-system
-- V2/V3/V4 migrations). Only loan and repayment columns are staged; no
-- customer personal data leaves the monolith. Rebuilt on every run, so the
-- stage always holds the latest snapshot (the reconcile compares against it).

\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS backfill_stage CASCADE;
CREATE SCHEMA backfill_stage;

CREATE TABLE backfill_stage.loans (
    id                     VARCHAR(36) PRIMARY KEY,
    customer_id            BIGINT         NOT NULL,
    loan_amount            NUMERIC(19, 2) NOT NULL,
    number_of_installments INTEGER        NOT NULL,
    interest_rate          NUMERIC(19, 3) NOT NULL,
    create_date            TIMESTAMP      NOT NULL,
    is_paid                BOOLEAN        NOT NULL,
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);

CREATE TABLE backfill_stage.loan_installments (
    id           VARCHAR(36) PRIMARY KEY,
    loan_id      VARCHAR(36)    NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    paid_amount  NUMERIC(19, 2) NOT NULL,
    due_date     DATE           NOT NULL,
    payment_date TIMESTAMP,
    is_paid      BOOLEAN        NOT NULL
);

-- COMPLETED payments only; run-backfill.sh refuses to run while any payment
-- is INITIATED or PROCESSING (those finish in the monolith before cutover).
CREATE TABLE backfill_stage.payments (
    id                 VARCHAR(36) PRIMARY KEY,
    loan_id            VARCHAR(36)    NOT NULL,
    payment_amount     NUMERIC(19, 2) NOT NULL,
    payment_date       TIMESTAMP      NOT NULL,
    installments_paid  INTEGER        NOT NULL,
    total_discount     NUMERIC(19, 2) NOT NULL,
    total_penalty      NUMERIC(19, 2) NOT NULL,
    is_loan_fully_paid BOOLEAN        NOT NULL
);

CREATE TABLE backfill_stage.payment_installments (
    payment_id     VARCHAR(36) NOT NULL,
    installment_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (payment_id, installment_id)
);
