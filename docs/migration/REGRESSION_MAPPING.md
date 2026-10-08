# Regression mapping: monolith loan API -> svc-ln-loan-lifecycle

Status: **Proposed**. For the regression parity suite (enterprise-loan-management-system PR #103,
`regression-parity`). The same content is published for the suite as
`regression/mappings/svc-ln-loan-lifecycle.md` in the shared project files.

## 1. Runnable head

| Field | Value |
|---|---|
| Repository | `fintechbankx-lendingpayments-loan-lifecycle-core` |
| Branch | `claude/project-thread-ty79y4` (PR #14) |
| Service id / app | `svc-ln-loan-lifecycle` / `app.ln.loan-lifecycle` |
| Base path | `/api/v1/loans` on port 8080 (actuator on 8081) |
| Customer service it was built against | `fintechbankx-customer-profile-kyc-core` branch `claude/customer-risk-compliance-deployable-ygi0zo` (PR #13), commit `c3596f2`: reserve, release and `GET .../credit` all return `CustomerCreditResponse`; the vendored contract copy (`loan-infrastructure/src/test/resources/contracts/customer-context.yaml`) is refreshed from commit `cf86385` |

## 2. Endpoints

Monolith side: `loan-context` `LoanController` (`/api/v1/loans`). The enhanced `LoanApiController`
endpoints are listed last; the service does not provide them.

| Method | Monolith path | Service path | Request changes | Response changes | Status changes |
|---|---|---|---|---|---|
| POST | `/api/v1/loans` | same | `currency` **required** (ISO 4217, `^[A-Z]{3}$`); no USD default. A customer's `customerId` must equal the token's `customer_id` claim | adds `rateBasis`, `flatTotalRate`, `currency`; `outstandingBalance` = schedule total (principal + interest); `monthlyPayment` = first installment | 400 missing/invalid currency; 403 other customer; 422 `INSUFFICIENT_CREDIT` / `CURRENCY_MISMATCH` / `CUSTOMER_NOT_FOUND`; 503 `CUSTOMER_SERVICE_UNAVAILABLE` |
| GET | `/api/v1/loans/{loanId}` | same | none | as above | 403 when a customer reads another customer's loan (monolith: 200); roles also LOAN_OFFICER, SERVICE |
| POST | `/api/v1/loans/{loanId}/approve` | same | none | as above | 409 `INVALID_LOAN_STATE` (monolith: 500) |
| POST | `/api/v1/loans/{loanId}/reject` | same | `reason` optional, max 500 | as above | 409 `INVALID_LOAN_STATE` |
| POST | `/api/v1/loans/{loanId}/disburse` | same | none | as above | 422 `INSUFFICIENT_CREDIT` (loan stays APPROVED), 409, 503 |
| POST | `/api/v1/loans/{loanId}/payments` | same | `amount` > 0 and `currency` (`^[A-Z]{3}$`, the loan's) required; optional header `x-idempotency-key` (max 128) | `outstandingBalance` falls by the amount paid, allocated interest first in installment order | 400 non-positive / over the balance / wrong currency; 403 other customer; 422 `IDEMPOTENCY_KEY_REUSED`; 409 `DUPLICATE_REQUEST` |
| POST | `/api/v1/loans/{loanId}/cancel` | same | `reason` optional, max 500 | as above | 403 other customer; 409 `INVALID_LOAN_STATE` |
| GET | `/api/v1/loans` (enhanced) | not provided | | | 405 (only POST on this path) |
| PUT | `/{loanId}/approve`, `/disburse`, `/reject` (enhanced, `Idempotency-Key`) | use the POST endpoints | | | 405 |
| GET | `/{loanId}/events`, `/amortization-schedule`, `/documents`, `/payments` (enhanced) | not provided | | | 404 (`/payments`: 405, only POST) |

Errors use `{code, message, interactionId, timestamp}` with stable codes (OpenAPI `ErrorResponse`).
Errors Spring resolves through `/error` keep their status (415, 405, 500), never 403.

### Interest representation (compare like with like)

| Field | Unit | Rate basis | Value |
|---|---|---|---|
| `annualInterestRate` | percent per year (6.5 = 6.5 %) | `NOMINAL_ANNUAL` (loans created by the service) | nominal rate, compounded monthly on the declining balance |
| `annualInterestRate` | percent per year | `FLAT_TOTAL` (loans migrated from the monolith) | display only: `flatTotalRate * 100 * 12 / termInMonths`, 4 decimals |
| `flatTotalRate` | fraction of principal over the whole term (0.200 = 20 %) | `FLAT_TOTAL` only, else null | the monolith's `loans.interest_rate` exactly as stored |

The loan-context API of the monolith takes `annualInterestRate` in percent, as the service does. Its
database (`loans.interest_rate`) holds the flat fraction, which the service returns as
`flatTotalRate` for migrated loans. Parity on migrated loans compares `flatTotalRate` with the
monolith's rate and `monthlyPayment` with the monolith's first installment (the backfill reconcile
checks the latter for every loan).

## 3. Intentional behaviour changes

Beyond the four already registered (INT-ERROR-MAPPING, INT-CURRENCY, INT-OWNERSHIP,
INT-SERVICE-CREDIT-CALLS):

1. **Currency required.** No default currency anywhere: requests without `currency` are 400; a loan
   in a currency the customer's credit is not held in is 422 `CURRENCY_MISMATCH`, never "insufficient
   credit". The backfill takes the book's currency as a required parameter.
2. **Customer credit-position API.** Credit is read from `GET /api/v1/customers/{id}/credit` and moved
   with `POST .../credit/reserve` and `.../credit/release` (keys `<loanId>:reserve`,
   `<loanId>:release`, `reference` = loanId) using this service's own client-credentials token.
   Unknown customer is 422 `CUSTOMER_NOT_FOUND`; customer service down or refusing the token is 503.
   A disbursement that cannot be stored cancels its reservation (monolith LN-18 leaked credit).
3. **Ownership and audience.** Tokens must carry `aud` = `svc-ln-loan-lifecycle`. A customer
   (realm role customer) is identified by the `customer_id` claim, not `sub` (a UUID under Keycloak),
   and gets 403 on another customer's loan for GET, payments and cancel, and when applying for
   another customer. A customer token without `customer_id` is 403. Staff and service tokens act on
   any loan. Registered as INT-OWNERSHIP; the monolith returns 200 (LN-16).
4. **In-flight repayments stay in the monolith.** Monolith payments still INITIATED or PROCESSING at
   cutover finish there and are not migrated; the backfill refuses to run while any exist.
5. **Schedule-based balances.** New loans get an amortised schedule at creation; `outstandingBalance`
   is everything still due (principal + scheduled interest), not the principal; payments are
   allocated interest first and recorded per installment; the loan is FULLY_PAID when the schedule is.
6. **Repayment idempotency.** Optional `x-idempotency-key` on payments: replay applies once.

## 4. Local boot without Kafka

```
DB_URL=jdbc:postgresql://localhost:5432/<db>
DB_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<password>
CUSTOMER_CREDIT_ADAPTER=in-memory          # or http + CUSTOMER_SERVICE_BASE_URL + OIDC_TOKEN_URI + SERVICE_CLIENT_SECRET
CUSTOMER_CREDIT_LEDGER_CURRENCY=AED        # required, no default
OUTBOX_RELAY_ENABLED=false                 # default; events stay in outbox_event
LOAN_REPAYMENT_CONSUMER_ENABLED=false      # default; no Kafka consumer
OIDC_ISSUER_URI=<issuer>  OIDC_JWK_SET_URI=<jwks>  OIDC_AUDIENCE=svc-ln-loan-lifecycle
./gradlew :loan-bootstrap:bootRun
```

With the in-memory adapter the monolith's stub customers are known: `CUST-12345678` (limit 100000,
used 0), `CUST-87654321` (50000 / 10000), `CUST-11111111` (25000 / 20000), all in the configured
ledger currency. These are the same ids and figures as the customer service's parity seed
(`db/fixtures/parity_seed_customers.sql` at `c3596f2`), which holds them in **USD**. For a parity run
set `CUSTOMER_CREDIT_LEDGER_CURRENCY=USD` (in-memory), or use `CUSTOMER_CREDIT_ADAPTER=http` against a
customer service loaded with that seed, and send `"currency": "USD"` on loan requests. Any other
currency is 422 `CURRENCY_MISMATCH` when the loan is applied for.
