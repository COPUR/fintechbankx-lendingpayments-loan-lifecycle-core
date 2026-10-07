-- Fixture with the column layout of the monolith's V2__Create_loans_table.sql
-- and V3__Create_loan_installments_table.sql (FKs and triggers left out).
CREATE TABLE loans (
    id VARCHAR(36) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    loan_amount DECIMAL(19,2) NOT NULL,
    number_of_installments INTEGER NOT NULL,
    interest_rate DECIMAL(19,3) NOT NULL,
    create_date TIMESTAMP NOT NULL,
    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE loan_installments (
    id VARCHAR(36) PRIMARY KEY,
    loan_id VARCHAR(36) NOT NULL REFERENCES loans(id),
    amount DECIMAL(19,2) NOT NULL,
    paid_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    due_date DATE NOT NULL,
    payment_date TIMESTAMP NULL,
    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

-- Loan A: 6 installments, all paid.  Loan B: 12 installments, 3 paid.
-- Loan C: 24 installments, none paid.
INSERT INTO loans (id, customer_id, loan_amount, number_of_installments, interest_rate, create_date, is_paid) VALUES
 ('11111111-1111-1111-1111-111111111111', 1, 6000.00, 6, 0.100, '2025-01-15 10:00', TRUE),
 ('22222222-2222-2222-2222-222222222222', 2, 12000.00, 12, 0.200, '2025-06-01 09:30', FALSE),
 ('33333333-3333-3333-3333-333333333333', 2, 24000.00, 24, 0.350, '2026-02-10 14:00', FALSE);

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('A' || n)::uuid::text, '11111111-1111-1111-1111-111111111111', 1100.00, 1100.00,
       DATE '2025-02-15' + make_interval(months => n - 1), TIMESTAMP '2025-02-10' + make_interval(months => n - 1), TRUE
  FROM generate_series(1, 6) n;

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('B' || n)::uuid::text, '22222222-2222-2222-2222-222222222222', 1200.00,
       CASE WHEN n <= 3 THEN 1200.00 ELSE 0 END,
       DATE '2025-07-01' + make_interval(months => n - 1),
       CASE WHEN n <= 3 THEN TIMESTAMP '2025-06-28' + make_interval(months => n - 1) END,
       n <= 3
  FROM generate_series(1, 12) n;

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('C' || n)::uuid::text, '33333333-3333-3333-3333-333333333333', 1350.00, 0, 
       DATE '2026-03-10' + make_interval(months => n - 1), NULL, FALSE
  FROM generate_series(1, 24) n;
