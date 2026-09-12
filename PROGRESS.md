# Build progress

Working notes. Each phase gets finished and tested before the next one starts, and each one
is its own commit.

**Now: Phase 3.** 48 tests green.

| Phase | What | State |
|---|---|---|
| 0 | Toolchain, teardown | done |
| 1 | Ledger core | done |
| 2 | Idempotency | done |
| 3 | Payment flow | in progress |
| 4 | Outbox | |
| 5 | HTTP API | |
| 6 | Customers, payment methods | |
| 7 | Webhooks | |
| 8 | Close out | |

---

## Phase 0 - Toolchain and teardown

- [x] Java 21 LTS, Spring Boot 3.5.5
- [x] Drop Kafka, Zookeeper, Kafka UI, Redis, JPA, Lombok, Jedis
- [x] Compose down to one Postgres 16 service
- [x] Maven wrapper restored (script-only, nothing to commit)
- [x] Compiles clean

## Phase 1 - Ledger core

- [x] `Amount` and `Currency`, with a split that returns its remainder
- [x] Flyway migration: accounts, transactions, entries, balance projection
- [x] `LedgerRepository`, hand-written SQL, `FOR UPDATE` in sorted id order
- [x] `LedgerService.post` as the only write path
- [x] Test: a posted transaction sums to zero
- [x] Test: the whole ledger sums to zero after 200 random transfers
- [x] Test: 20 concurrent debits, no lost updates
- [x] Test: 20 threads race for 5 funded transfers, exactly 5 win
- [x] Test: 30 opposing transfers on one pair, no deadlock
- [x] Test: balance projection never drifts from the entries
- [x] Test: UPDATE and DELETE on entries are no-ops

40 tests.

## Phase 2 - Idempotency

- [x] `idempotency_keys` table, unique on the key
- [x] Claim by insert, so the check and the claim are one step
- [x] Request body hashed; same key with a different body is rejected
- [x] Losers lock the winner's row and replay its response
- [x] Test: 10 concurrent identical requests move money once
- [x] Test: a repeat request doesn't rerun the work
- [x] Test: same key, different body, gets 422
- [x] Test: a failed request leaves no claim behind

48 tests.

Worth remembering: catching `DuplicateKeyException` doesn't work here. Postgres aborts the
whole transaction on a constraint violation, so the next statement fails with "current
transaction is aborted" no matter what Java does with the exception. Since the caller has
to read the existing row in that same transaction, the conflict has to be swallowed by the
database instead. `ON CONFLICT DO NOTHING`. Three tests caught it.

## Phase 3 - Payment flow

- [ ] Payment intent state machine, transitions in one table
- [ ] Authorize: customer to hold account
- [ ] Capture: hold to merchant, remainder released on partial capture
- [ ] Cancel and refund
- [ ] Test: capture once, customer debited exactly once
- [ ] Test: partial capture releases the rest, hold account ends at zero
- [ ] Test: every illegal transition is refused

## Phase 4 - Outbox

- [ ] `outbox` table, written inside the business transaction
- [ ] Poller with backoff
- [ ] Test: a rolled-back transaction publishes nothing
- [ ] Test: redelivery after a crash, at-least-once

## Phase 5 - HTTP API

- [ ] `/v1/payment_intents` create, retrieve, list
- [ ] `/confirm`, `/capture`, `/cancel`, and `/v1/refunds`
- [ ] `Idempotency-Key` header wired through
- [ ] Prefixed ids, Stripe's error and list envelopes
- [ ] Cursor pagination
- [ ] Test: error bodies match Stripe's shape

## Phase 6 - Customers and payment methods

- [ ] `/v1/customers` CRUD
- [ ] `/v1/payment_methods`, attach and detach
- [ ] Stripe's test card numbers, deterministic outcomes
- [ ] Test: each card produces its documented result and the right ledger state

## Phase 7 - Webhooks

- [ ] Endpoint registration
- [ ] `Stripe-Signature`, HMAC-SHA256 over timestamp and body
- [ ] Retry with backoff, attempt log
- [ ] `/v1/events`
- [ ] Test: signature verifies, tampered body rejected
- [ ] Test: failing endpoint retried on schedule, gives up eventually

## Phase 8 - Close out

- [ ] OpenAPI doc
- [ ] Health check that asserts the ledger balances
- [ ] `docker compose up` runs the app too
- [ ] README final pass

---

## Notes to self

Things that bit me, so I don't repeat them.

**Docker 29 and Testcontainers.** Testcontainers 1.21 negotiates Docker API 1.32, Engine 29
wants 1.40 minimum. The engine replies with an empty HTTP 400 and Testcontainers turns that
into "Could not find a valid Docker environment", which sent me looking in completely the
wrong place. Fix is `api.version=1.44` as a system property in the Surefire config. Setting
it as `DOCKER_API_VERSION` in the environment does nothing, and neither does putting
`api.version` in `testcontainers.properties`.

**Windows named pipe.** Docker Desktop serves on `dockerDesktopLinuxEngine`, not the
`docker_engine` pipe Testcontainers probes. Set through an OS-activated Maven profile so
Linux and macOS aren't affected.
