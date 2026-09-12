# Stripe Clone

A payments API modelled on Stripe's, built ledger-first: a correct double-entry ledger
underneath, Stripe's API surface on top.

> **Status: rebuild in progress.** This README is the plan. The v1 scaffold
> (Kafka, Redis, JPA) is being removed; see `Migration from v1` at the bottom for what
> is going and why. Nothing below is claimed as working until it has a test next to it.

---

## What this is

Stripe's public API — payment intents, charges, refunds, customers, webhooks — implemented
over an append-only double-entry ledger that holds under concurrency.

The API surface is the recognizable part. The ledger is the part that's hard. Most
payments side-projects get this backwards: they build the endpoints, store balances in a
mutable column, and ship something that quietly loses money when two requests race. This
one builds the ledger first, proves it with concurrency tests, and puts the API on top of
a foundation that can't drift.

**Build order is ledger → idempotency → payments → outbox → API → customers → webhooks.**
Each phase is tested before the next begins. That ordering is the whole method.

### What "clone" means here, precisely

This clones Stripe's **API surface** and its **internal accounting model**. It does not
clone Stripe's **rails**: there is no card network authorization, no 3-D Secure, no real
bank settlement, and no PCI scope, because those require bank relationships rather than
code. Authorization is simulated at the boundary; everything behind that boundary is real.

That boundary is stated once here and not belaboured again.

---

## Design decisions

Each of these is a decision I would defend in a code review, with the reasoning attached.

### Money is `long` minor units, never `BigDecimal` or `double`

An `Amount` is a `long` count of the currency's smallest unit plus an ISO-4217 code.
1000 USD minor units is $10.00. This removes rounding and scale bugs by construction:
there is no representation for half a cent, so there is no question of what to do with one.

It is also exactly what Stripe does, so the wire format falls out for free — `"amount": 1000`
in JSON is the same integer the ledger stores.

Division, where it appears (fee splits), returns an explicit remainder the caller must
allocate. It cannot silently vanish.

### The ledger is append-only; balances are derived

There is no mutable `balance` column that code updates. `ledger_entries` rows are
inserted and never updated or deleted. An account's balance is the sum of its entries.

This is the single most important decision in the project. The v1 scaffold stored
balances as mutable fields, and that is exactly how it ended up double-debiting the
customer on capture: two different code paths each subtracted from the same field.
A ledger that can only be appended to cannot drift from its own history.

For read performance there is an `account_balances` projection table, maintained in the
same transaction as the entries and rebuildable from scratch at any time. The test suite
asserts the projection equals the recomputed sum.

### Every transaction sums to zero

A `ledger_transactions` row has two or more `ledger_entries`. Signed amounts across a
transaction must sum to exactly zero. This is enforced three times, deliberately:

1. In the domain, when the transaction is constructed.
2. By a database `CHECK` on the deferred sum, so no code path can bypass it.
3. By a test asserting the whole ledger sums to zero after every operation.

Redundant enforcement is the point. A ledger that balances only because the application
remembered to check is not a ledger.

### Holds are ledger entries, not a separate balance field

Authorizing moves money from a customer's available account into a per-hold pending
account. Capturing moves it from the hold account to the merchant. Voiding moves it back.
Every state change is a transaction, so the hold lifecycle is fully auditable and cannot
disagree with the balance.

This is what makes partial capture correct for free: capturing 60 of a 100 hold moves 60
to the merchant and 40 back to the customer, in one balanced transaction.

### Idempotency lives in Postgres, in the same transaction as the write

An `idempotency_keys` table with a unique constraint on the key. The request is inserted
and the business write happens in one transaction. A concurrent duplicate loses the
unique-constraint race and reads back the first response.

The v1 design cached responses in Redis with a check-then-set, which is not atomic and
can diverge from the database. One database, one transaction, one source of truth.

The stored record includes a hash of the request body, so reusing a key with different
parameters returns `422` rather than the wrong cached response. This is how Stripe behaves.

### Events go to an outbox table, not directly to a broker

A `payment_intent.succeeded` event is inserted into an `outbox` table inside the same
transaction that writes the ledger entries. Either both commit or neither does. A separate
poller reads unpublished rows and delivers them.

This is the transactional outbox pattern, and it is the honest version of what v1's README
called "exactly-once". v1 published to Kafka inside the transaction with an async send
whose failure nobody could observe: if the transaction rolled back, the event had already
gone out.

There is no Kafka here. The outbox with a poller gives the same at-least-once guarantee
with one fewer moving part — and it is precisely the machinery webhooks need in Phase 7,
so it pays for itself twice.

### Spring JDBC and hand-written SQL, not JPA

Every query is visible in the repository class. Lock acquisition is explicit and greppable.
For a project whose entire subject is transactional correctness, hiding the SQL behind an
ORM removes the thing worth looking at. There is no lazy loading, no cascade semantics to
reason about, and no auto-DDL quietly making the migration files dead code.

### Concurrency control is explicit

Account rows are locked with `SELECT ... FOR UPDATE` in a deterministic order (sorted by
account id) to prevent deadlocks between transactions touching the same pair of accounts.
The ordering rule lives in one place and is covered by a concurrent transfer test that
would deadlock without it.

---

## Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 LTS | Records, sealed interfaces, pattern matching, virtual threads |
| Framework | Spring Boot 3.5 (web, jdbc, actuator) | No JPA, no Kafka, no Redis starters |
| Database | PostgreSQL 16 | Real transactions, `FOR UPDATE`, deferred constraints |
| Data access | Spring `JdbcClient` | Hand-written SQL, explicit locking |
| Migrations | Flyway | Versioned, runs on startup, auto-DDL off |
| Build | Maven wrapper | Self-contained, no global Maven needed |
| Tests | JUnit 5, Testcontainers, AssertJ | Integration tests against real Postgres |
| Infra | Docker Compose | One service: Postgres |

Removed from v1: Kafka, Zookeeper, Kafka UI, Redis, Lombok, Hibernate/JPA, Jedis.

---

## Domain model

```
accounts                 account_id, type, currency, created_at
ledger_transactions      txn_id, kind, created_at, idempotency_key
ledger_entries           entry_id, txn_id, account_id, amount (signed), currency, created_at
account_balances         account_id, balance  (projection, rebuildable)
idempotency_keys         key, request_hash, response_body, status, created_at
outbox                   id, aggregate_id, type, payload, created_at, published_at
payment_intents          id, customer_id, amount, currency, status, capture_method
charges                  id, payment_intent_id, amount_captured, amount_refunded, status
customers                id, email, name, created_at
payment_methods          id, customer_id, type, brand, last4, exp_month, exp_year
webhook_endpoints        id, url, secret, enabled_events
webhook_deliveries       id, endpoint_id, event_id, attempts, status, next_attempt_at
```

Account types: `CUSTOMER`, `MERCHANT`, `HOLD`, `SETTLEMENT`, `FEE`, `EXTERNAL`.

`EXTERNAL` is the counterparty for money entering or leaving the system, so funding a
customer account is still a balanced transaction rather than money appearing from nowhere.

### Payment intent lifecycle

Stripe's status values, backed by the ledger transactions underneath.

```
  requires_payment_method
            │ attach method
            ▼
     requires_confirmation
            │ confirm
            ▼
      requires_capture ──────── cancel ────────► canceled
            │ capture (full or partial)
            ▼
        succeeded ──────────── refund ─────────► refunded
            │ settle
            ▼
         settled
```

Transitions live in one place as an explicit table. Any other transition throws.

---

## Build order

Each phase ends with something that runs and is tested. No phase starts before the
previous one is green.

### Phase 0 — Toolchain and teardown

- [x] JDK 21 LTS installed locally
- [x] Maven 3.9.5 available
- [ ] Restore Maven wrapper (`mvnw`, `mvnw.cmd`, wrapper jar)
- [ ] Delete Kafka, Redis, JPA, and Lombok code and dependencies
- [ ] Compose file down to one Postgres 16 service
- [ ] `./mvnw compile` green

### Phase 1 — The ledger core

- [ ] `Amount` and `Currency` value types with arithmetic and a remainder-returning split
- [ ] Flyway migrations for accounts, transactions, entries, balances
- [ ] `LedgerRepository` with explicit SQL and ordered `FOR UPDATE`
- [ ] `LedgerService.post(transaction)` — the only write path into the ledger
- [ ] **Test: a posted transaction always sums to zero**
- [ ] **Test: the whole ledger sums to zero after N random transfers**
- [ ] **Test: 20 concurrent transfers on one account, no lost updates, no deadlock**
- [ ] **Test: the balance projection equals the recomputed sum**

### Phase 2 — Idempotency

- [ ] `idempotency_keys` table and unique constraint
- [ ] `IdempotencyService` wrapping a write in the same transaction
- [ ] Request-hash mismatch returns `422`
- [ ] **Test: two concurrent identical requests produce one ledger transaction**
- [ ] **Test: same key, different body, returns 422**

### Phase 3 — Payment flow on the ledger

- [ ] Payment intent state machine with an explicit transition table
- [ ] Authorize: customer → hold account
- [ ] Capture: hold → merchant, remainder released to customer on partial capture
- [ ] Cancel and refund
- [ ] **Test: the v1 double-debit bug, pinned — capture once, customer down exactly once**
- [ ] **Test: partial capture releases the remainder, hold account ends at zero**

### Phase 4 — Outbox

- [ ] `outbox` table written inside the business transaction
- [ ] Poller with backoff, marking rows published
- [ ] **Test: a rolled-back transaction publishes nothing**
- [ ] **Test: a crashed poller redelivers, consumers see at-least-once**

### Phase 5 — Stripe-shaped HTTP API

The API mirrors Stripe's conventions closely enough that their docs read as a spec.

- [ ] `POST /v1/payment_intents`, `GET /v1/payment_intents/:id`, `GET /v1/payment_intents`
- [ ] `POST /v1/payment_intents/:id/confirm`, `/capture`, `/cancel`
- [ ] `POST /v1/refunds`
- [ ] `Idempotency-Key` header wired to Phase 2
- [ ] Prefixed ids: `pi_`, `ch_`, `re_`, `cus_`, `pm_`, `evt_`
- [ ] Stripe's error envelope: `{"error": {"type", "code", "message", "param"}}`
- [ ] Stripe's list envelope: `{"object": "list", "data": [...], "has_more": bool}`
- [ ] Cursor pagination via `starting_after` / `ending_before`
- [ ] `@RestControllerAdvice` actually reachable (v1's was not — controllers swallowed everything)
- [ ] **Test: error responses match Stripe's shape for each failure mode**

### Phase 6 — Customers and payment methods

- [ ] `POST /v1/customers` and the rest of CRUD
- [ ] `POST /v1/payment_methods`, `/attach`, `/detach`
- [ ] Test card numbers that trigger deterministic outcomes (`4242…` succeeds,
      `4000000000000002` declines, `4000000000009995` insufficient funds)
- [ ] Simulated authorization at the network boundary, real ledger behind it
- [ ] **Test: each test card produces its documented outcome and correct ledger state**

### Phase 7 — Webhooks

Built directly on the Phase 4 outbox, which is the payoff for that design.

- [ ] `webhook_endpoints` registration
- [ ] Event types: `payment_intent.created`, `.succeeded`, `.canceled`,
      `charge.refunded`, `charge.captured`
- [ ] `Stripe-Signature` header: HMAC-SHA256 over timestamp and payload
- [ ] Retry with exponential backoff, delivery attempt log
- [ ] `GET /v1/events` to replay what was sent
- [ ] **Test: signature verifies with the endpoint secret and rejects a tampered body**
- [ ] **Test: a failing endpoint is retried on schedule and gives up after N attempts**

### Phase 8 — Close out

- [ ] OpenAPI document generated from the controllers
- [ ] Actuator health including a ledger-balance check
- [ ] `docker compose up` runs the whole thing including the app
- [ ] README rewritten to describe what exists, in the past tense

---

## Running it

Nothing to run yet. When Phase 1 lands:

```bash
docker compose up -d          # Postgres only
./mvnw spring-boot:run
```

Tests need Docker running, since Testcontainers starts a real Postgres:

```bash
./mvnw test
```

---

## Migration from v1

The first version of this repo was a one-day scaffold. It described itself as
"bank-grade" and "production-ready" with zero tests, and it had a ledger bug that
double-debited every captured payment. This rebuild keeps the domain sketch and
throws away the infrastructure.

| v1 | v2 | Reason |
|---|---|---|
| Kafka + Zookeeper + Kafka UI | Outbox table + poller | Consumers were empty stubs; "exactly-once" was never implemented |
| Redis idempotency | Postgres unique constraint | Check-then-set was not atomic; cache could diverge from the database |
| JPA / Hibernate | Spring JDBC | Hid the locking and SQL that are the point of the project |
| `BigDecimal` money | `long` minor units | Removes rounding and scale bugs by construction; matches Stripe's wire format |
| Mutable balance columns | Append-only entries | The mutable fields were the direct cause of the double-debit |
| Lombok | Java records | One less annotation processor |
| JPA auto-DDL | Flyway | v1's migration files were dead code |
| Ad-hoc REST shape | Stripe's API conventions | Recognizable surface makes the correctness underneath legible |

The v1 code is reachable in git history at commit `89d1b7c`.

## License

MIT
