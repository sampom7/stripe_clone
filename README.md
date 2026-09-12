# Stripe Clone

A payments API modelled on Stripe's, backed by a double-entry ledger.

Payment intents, charges, refunds, customers and webhooks, implemented over an append-only
ledger that holds up under concurrent load. The API surface is the recognisable part; the
ledger underneath is the part that was actually hard to get right.

This is a learning project. It clones Stripe's API shape and its internal accounting model,
not its rails: no card networks, no 3-D Secure, no real bank settlement, no PCI scope.
Authorization is simulated at the boundary. Everything behind that boundary is real.

## Why build it this way

Most payment side-projects start from the endpoints, keep balances in a column somewhere
and update them as money moves. That works until two requests touch the same account at the
same time, and then it quietly loses money in a way that's very hard to notice.

So this one starts underneath. The ledger came first, with concurrency tests to prove it,
and the API went on top afterwards.

The core rules:

**Money is a `long` count of minor units.** 1000 USD minor units is ten dollars. No floats,
no `BigDecimal`. There's no way to represent half a cent, so there's never a question about
what to do with one. Stripe does the same thing, which means the JSON on the wire is the
same integer the database stores.

**Nothing updates a balance.** Ledger entries are inserted and never changed. An account's
balance is the sum of its entries. There's a projection table for fast reads, but it's
derived, and it can be thrown away and rebuilt from the entries at any point.

**Every transaction sums to zero.** Entries are signed, debits negative and credits
positive, and they have to cancel out. That's checked when the transaction object is built,
again by a deferred trigger in Postgres, and again by a test that recomputes the whole
ledger. Three times is deliberate. A ledger that balances only because the application code
remembered to check isn't really a ledger.

**Holds are entries too.** Authorizing moves money into a hold account. Capturing moves it
out to the merchant. Voiding sends it back. There's no separate "held balance" field that
could drift out of step, and partial capture falls out of the design for free: capture 60
of a 100 hold and the other 40 goes back to the customer in the same balanced transaction.

**Idempotency is a unique constraint.** The key row and the write it protects share one
database transaction. If the write rolls back, the key goes with it and the caller can
retry. No cache, so nothing to get out of sync.

**Events go to an outbox table.** Written in the same transaction as the ledger entries,
picked up by a poller afterwards. If the transaction rolls back, no event was ever sent.
The same table drives webhook delivery.

## Stack

| | |
|---|---|
| Java | 21 LTS |
| Framework | Spring Boot 3.5, web + JDBC + actuator |
| Database | PostgreSQL 16 |
| Data access | Spring `JdbcClient`, hand-written SQL |
| Migrations | Flyway |
| Tests | JUnit 5, AssertJ, Testcontainers |

No ORM, on purpose. The whole subject of this project is transactional correctness, and an
ORM would hide exactly the SQL and the lock acquisition worth looking at.

No message broker either. An outbox table with a poller gives the same guarantee with one
fewer moving part to run.

## Running it

Needs Java 21 and Docker.

```bash
docker compose up -d      # postgres
./mvnw spring-boot:run    # migrations run on startup
```

Tests spin up their own Postgres through Testcontainers, so the compose stack isn't needed
for them:

```bash
./mvnw test
```

### If Testcontainers can't find Docker

Testcontainers 1.21 talks Docker API 1.32, and Docker Engine 29 refuses anything below
1.40. It answers with a bare HTTP 400, and the resulting error is "Could not find a valid
Docker environment", which sounds like Docker isn't installed rather than too new. The
build pins `api.version=1.44` in the Surefire config to get around it, and resolves the
Docker Desktop named pipe on Windows in the same place.

## API

Endpoints follow Stripe's conventions: `/v1/` paths, prefixed ids (`pi_`, `ch_`, `re_`,
`cus_`, `pm_`, `evt_`), an `Idempotency-Key` header on writes, amounts as integers, and
Stripe's error and list envelopes.

```bash
curl -X POST http://localhost:8080/v1/payment_intents \
  -H "Idempotency-Key: key_12345" \
  -H "Content-Type: application/json" \
  -d '{"amount": 2000, "currency": "usd", "customer": "cus_abc"}'
```

## Repo layout

```
src/main/java/com/stripeclone/
  money/         Amount, Currency. No other package does arithmetic on money.
  ledger/        Accounts, entries, transactions. The only write path is LedgerService.post.
  idempotency/   Key claiming and response replay.
  payment/       Payment intents, charges, refunds. Sits on top of the ledger.
  outbox/        Event table and the poller that drains it.
  webhook/       Endpoint registration, signing, delivery retries.
  api/           Controllers, DTOs, error mapping.
```

Build status and the phase-by-phase plan are in [PROGRESS.md](PROGRESS.md).

## License

MIT
