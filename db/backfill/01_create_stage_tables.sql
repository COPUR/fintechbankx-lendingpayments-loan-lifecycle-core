-- Step 1 of the loan data split: staging tables in the LOAN SERVICE database
-- that receive a CSV copy of the monolith's loans and loan_installments
-- (enterprise-loan-management-system V2/V3 migrations). Only loan columns are
-- staged; no customer personal data leaves the monolith.

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
