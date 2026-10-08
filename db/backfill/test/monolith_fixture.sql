-- Fixture with the column layout of the monolith's V2__Create_loans_table.sql,
-- V3__Create_loan_installments_table.sql and V4__Create_payments_table.sql
-- (triggers left out). The monolith keys customers by a numeric id; the
-- customer service keeps it as text ("1", "2").

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

CREATE TABLE payments (
    id VARCHAR(36) PRIMARY KEY,
    loan_id VARCHAR(36) NOT NULL REFERENCES loans(id),
    payment_amount DECIMAL(19,2) NOT NULL CHECK (payment_amount > 0),
    payment_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    installments_paid INTEGER NOT NULL DEFAULT 0,
    total_discount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    total_penalty DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    is_loan_fully_paid BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payments_status_valid CHECK (payment_status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE payment_installments (
    payment_id VARCHAR(36) NOT NULL REFERENCES payments(id),
    installment_id VARCHAR(36) NOT NULL REFERENCES loan_installments(id),
    PRIMARY KEY (payment_id, installment_id)
);

-- Loan A: 6000.00, flat 10% over 6 -> 6 x 1100.00, all paid by one payment.
-- Loan B: 12000.00, flat 20% over 12 -> 12 x 1200.00, 3 paid: installment 1 on its
--         own, 2 (late, +5.00 penalty) and 3 (early, -10.00 discount) together.
-- Loan C: 24000.00, flat 35% over 24 -> 24 x 1350.00, none paid; one FAILED payment.
-- Loan D: 10000.00, flat 20% over 9 -> 9 x 1333.33 (the monolith's rounding), none paid.
INSERT INTO loans (id, customer_id, loan_amount, number_of_installments, interest_rate, create_date, is_paid, updated_at) VALUES
 ('11111111-1111-1111-1111-111111111111', 1, 6000.00, 6, 0.100, '2025-01-15 10:00', TRUE, '2025-07-10 00:00'),
 ('22222222-2222-2222-2222-222222222222', 2, 12000.00, 12, 0.200, '2025-06-01 09:30', FALSE, '2025-08-27 00:00'),
 ('33333333-3333-3333-3333-333333333333', 2, 24000.00, 24, 0.350, '2026-02-10 14:00', FALSE, '2026-02-10 14:00'),
 ('44444444-4444-4444-4444-444444444444', 1, 10000.00, 9, 0.200, '2026-03-01 08:00', FALSE, '2026-03-01 08:00');

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('A' || n)::uuid::text, '11111111-1111-1111-1111-111111111111', 1100.00, 1100.00,
       DATE '2025-02-15' + make_interval(months => n - 1), TIMESTAMP '2025-07-10', TRUE
  FROM generate_series(1, 6) n;

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('B' || n)::uuid::text, '22222222-2222-2222-2222-222222222222', 1200.00,
       CASE n WHEN 1 THEN 1200.00 WHEN 2 THEN 1205.00 WHEN 3 THEN 1190.00 ELSE 0 END,
       DATE '2025-07-01' + make_interval(months => n - 1),
       CASE WHEN n = 1 THEN TIMESTAMP '2025-06-28' WHEN n IN (2, 3) THEN TIMESTAMP '2025-08-27' END,
       n <= 3
  FROM generate_series(1, 12) n;

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('C' || n)::uuid::text, '33333333-3333-3333-3333-333333333333', 1350.00, 0,
       DATE '2026-03-10' + make_interval(months => n - 1), NULL, FALSE
  FROM generate_series(1, 24) n;

INSERT INTO loan_installments (id, loan_id, amount, paid_amount, due_date, payment_date, is_paid)
SELECT md5('D' || n)::uuid::text, '44444444-4444-4444-4444-444444444444', 1333.33, 0,
       DATE '2026-04-01' + make_interval(months => n - 1), NULL, FALSE
  FROM generate_series(1, 9) n;

INSERT INTO payments (id, loan_id, payment_amount, payment_date, payment_status, installments_paid,
                      total_discount, total_penalty, is_loan_fully_paid) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 6600.00, '2025-07-10', 'COMPLETED', 6, 0, 0, TRUE),
 ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 1200.00, '2025-06-28', 'COMPLETED', 1, 0, 0, FALSE),
 ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 2395.00, '2025-08-27', 'COMPLETED', 2, 10.00, 5.00, FALSE),
 ('cccccccc-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 1350.00, '2026-03-05', 'FAILED', 0, 0, 0, FALSE);

INSERT INTO payment_installments (payment_id, installment_id)
SELECT 'aaaaaaaa-0000-0000-0000-000000000001', md5('A' || n)::uuid::text FROM generate_series(1, 6) n
UNION ALL SELECT 'bbbbbbbb-0000-0000-0000-000000000001', md5('B1')::uuid::text
UNION ALL SELECT 'bbbbbbbb-0000-0000-0000-000000000002', md5('B2')::uuid::text
UNION ALL SELECT 'bbbbbbbb-0000-0000-0000-000000000002', md5('B3')::uuid::text;
