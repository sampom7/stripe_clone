# Build progress

Working notes. Each phase gets finished and tested before the next one starts, and each one
is its own commit.

**Done.** 190 tests green.

| Phase | What | State |
|---|---|---|
| 0 | Toolchain, teardown | done |
| 1 | Ledger core | done |
| 2 | Idempotency | done |
| 3 | Payment flow | done |
| 4 | Outbox | done |
| 5 | HTTP API | done |
| 6 | Customers, payment methods | done |
| 7 | Webhooks | done |
| 8 | Close out | done |

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

- [x] Payment intent state machine, transitions in one table
- [x] Authorize: customer to a per-intent hold account
- [x] Capture: hold to merchant, remainder released on partial capture
- [x] Cancel and refund, full and partial
- [x] Test: capture once, customer debited exactly once
- [x] Test: partial capture releases the rest, hold account ends at zero
- [x] Test: every illegal transition is refused
- [x] Test: ledger still balances after a mixed run of all four operations

66 tests.

Each intent gets its own hold account rather than a "held" column on the customer. The
amount held is then just that account's balance, so there's no second number to keep in
step. Partial capture falls out of it: debit the whole hold, credit the merchant what was
asked for, credit the customer the rest, one balanced transaction, hold ends at zero.

## Phase 4 - Outbox

- [x] `outbox` table, written inside the business transaction
- [x] Poller on a timer, `FOR UPDATE SKIP LOCKED` so several can run
- [x] Doubling backoff, capped at an hour, gives up after 8 attempts
- [x] Payment operations emit Stripe-named events
- [x] Test: a rolled-back transaction publishes nothing
- [x] Test: a failed payment leaves no succeeded event
- [x] Test: redelivery after a crash, at-least-once
- [x] Test: a failing handler is retried, not dropped
- [x] Test: events go out oldest first

79 tests.

The claim and the delivery share a transaction, so a crash mid-delivery rolls the claim
back and the event gets retried instead of vanishing. Cost is that a slow handler holds a
row lock, hence the small batch size.

At-least-once, not exactly-once. Crash after the handler succeeds but before the commit and
the event goes out twice. Nothing to be done about that short of two-phase commit, so
handlers dedupe on event id.

The backoff cap was dead code at first: the shift was clamped to 10, giving 2048 seconds,
so the hour cap never came into play. A test caught it.

## Phase 5 - HTTP API

- [x] `/v1/payment_intents` create, retrieve, list
- [x] `/confirm`, `/capture`, `/cancel`, plus `/v1/refunds`, `/v1/charges`, `/v1/customers`
- [x] `Idempotency-Key` header wired through to Phase 2
- [x] Prefixed ids, Stripe's error and list envelopes
- [x] Cursor pagination with `starting_after`
- [x] Test: error bodies match Stripe's shape for each failure mode
- [x] Test: same key same body returns the same object, different body is 422

97 tests.

Controllers don't catch anything, which is the point. Every failure goes to one
`@RestControllerAdvice`. A declined payment is 402 with a decline code, a missing object is
404 with `resource_missing`, a bad transition is 400, an idempotency clash is 422. The
first version of this project wrapped every handler in a try/catch returning 500, so the
advice never ran and a typo'd id looked the same as a database outage.

`has_more` comes from fetching limit+1 rows and trimming, which avoids a second count
query.

## Phase 6 - Customers and payment methods

- [x] `/v1/customers` create and retrieve
- [x] `/v1/payment_methods`, attach and detach
- [x] Stripe's published test card numbers, deterministic outcomes
- [x] Luhn check, brand detection, fingerprints
- [x] Confirm with a card runs it past the simulated network first
- [x] Test: each card produces its documented result and the right ledger state
- [x] Test: the card number is never stored anywhere

147 tests.

Worth keeping straight: a card can fail two different ways and they mean different things.
The network refusing it is `CardDeclinedException`. The network approving it and the ledger
then refusing because the money isn't there is `InsufficientFundsException`. Both come back
as 402, but the decline codes differ, and only one of them says anything about the balance.

An unrecognised card number declines rather than approves. Defaulting the other way would
mean a typo in a test passes silently.

Caught by a test: `@Valid` doesn't cascade into a nested record on its own. Without `@Valid`
on the field itself the inner constraints never run, so a bad expiry month sailed past bean
validation and hit the database CHECK instead, which came back as a 500.

## Phase 7 - Webhooks

- [x] Endpoint registration, enable/disable/delete, per-endpoint secret
- [x] `Stripe-Signature`, HMAC-SHA256 over timestamp and body
- [x] Fan-out handler on the Phase 4 outbox
- [x] Separate delivery poller, backoff from a minute to a day
- [x] `/v1/events` for catching up after an outage, plus delivery replay
- [x] Test: signature verifies, tampered body rejected, stale timestamp rejected
- [x] Test: failing endpoint retried on schedule, gives up eventually

187 tests.

Two pollers, not one. The outbox poller must never block on anything external, and this one
talks to endpoints that may be slow or gone. Sharing would let one dead receiver hold up
event processing for everyone, since the outbox poller keeps a row lock while its handler
runs. So the fan-out handler only writes delivery rows and returns.

The timestamp goes *inside* the signed string, not just next to it. If it were only a
header field you could replay yesterday's body with today's timestamp and the signature
would still check out. There's a test for exactly that.

Comparison uses `MessageDigest.isEqual` rather than `String.equals`. Normal equals bails
out at the first differing byte, and how long that took leaks how many leading characters
were right, which is enough to rebuild a valid signature one character at a time.

The unique constraint on (endpoint, event) is what makes fan-out safe to repeat, which
matters because the outbox is at-least-once and the handler will see some events twice.

## Phase 8 - Close out

- [x] OpenAPI at `/v1/openapi.json`, Swagger UI at `/docs`
- [x] `/actuator/health/ledger` sums the entries and checks the projection
- [x] Dockerfile, and an `app` compose profile that runs the whole stack
- [x] End-to-end smoke test over HTTP
- [x] README final pass

190 tests.

The health check is the interesting one. Normal health checks tell you the process is up
and the database answers, neither of which says the system is still correct. This one sums
every entry and compares the projection against what the entries say. If it goes down the
answer isn't to restart, it's to stop writing and go read the ledger.

Host ports in compose are overridable. 5432 was already taken by another project's Postgres
on my machine, which would have been an annoying first five minutes for anyone cloning this.

Verified by actually running it: built the image, brought up the stack, took a payment,
had a card declined with the right code, confirmed the ledger still balanced and the events
were recorded.

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
