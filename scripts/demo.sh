#!/usr/bin/env bash
#
# Walks through a full payment against a running instance, printing what comes back.
#
#   ./scripts/demo.sh                       # against http://localhost:8080
#   BASE=http://localhost:9000 ./scripts/demo.sh
#
# Needs the app running and reachable. Money has to be seeded straight into the database,
# because there's no funding endpoint: in a real processor money arrives from card
# networks, not from an API call. That means this script also needs the Postgres container
# to be reachable by name.

set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PG_CONTAINER="${PG_CONTAINER:-stripe-clone-postgres}"
RUN_ID="demo-$(date +%s)"

bold()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
dim()   { printf '\033[2m%s\033[0m\n' "$1"; }
field() { python -c "import sys,json;print(json.load(sys.stdin)['$1'])"; }

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "need $1 on PATH"; exit 1; }
}
require curl
require python
require docker

bold "0. Is it up?"
if ! curl -sf "$BASE/actuator/health" >/dev/null; then
  echo "Nothing answering at $BASE."
  echo "Start it with:  docker compose --profile app up -d"
  exit 1
fi
curl -s "$BASE/actuator/health/ledger"
echo

# ---------------------------------------------------------------- setup

bold "1. A merchant to be paid"
# Accounts that aren't customers have no endpoint, so they go in directly.
docker exec -i "$PG_CONTAINER" psql -U postgres -d stripe_clone -q <<SQL
INSERT INTO accounts (account_id, account_type, currency)
     VALUES ('ext_usd', 'EXTERNAL', 'USD') ON CONFLICT DO NOTHING;
INSERT INTO account_balances (account_id, balance, currency)
     VALUES ('ext_usd', 0, 'USD') ON CONFLICT DO NOTHING;
INSERT INTO accounts (account_id, account_type, currency)
     VALUES ('merch_demo', 'MERCHANT', 'USD') ON CONFLICT DO NOTHING;
INSERT INTO account_balances (account_id, balance, currency)
     VALUES ('merch_demo', 0, 'USD') ON CONFLICT DO NOTHING;
SQL
dim "merchant account: merch_demo"

bold "2. Create a customer"
CUSTOMER=$(curl -s -X POST "$BASE/v1/customers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-cus" \
  -d '{"email":"demo@example.com","name":"Demo Buyer","currency":"usd"}')
echo "$CUSTOMER"
CUSTOMER_ID=$(echo "$CUSTOMER" | field id)

bold "3. Give them \$500 to spend"
# Funding is a balanced transaction: EXTERNAL goes negative by exactly what the customer
# gains, so the ledger still sums to zero.
#
# Both entries go in as ONE statement. The balance trigger is deferred to commit but
# evaluated per row, so inserting the debit on its own trips "needs at least 2 entries".
# ON_ERROR_STOP matters too: without it psql would sail past a failed insert and still
# apply the balance updates below, leaving the projection claiming money the ledger never
# recorded. Which is exactly what happened the first time I wrote this.
docker exec -i "$PG_CONTAINER" psql -U postgres -d stripe_clone -q -v ON_ERROR_STOP=1 <<SQL
BEGIN;

INSERT INTO ledger_transactions (txn_id, kind, currency, description)
     VALUES ('txn_$RUN_ID', 'FUNDING', 'USD', 'demo funding');

INSERT INTO ledger_entries (txn_id, account_id, amount, currency)
     SELECT 'txn_$RUN_ID', 'ext_usd', -50000, 'USD'
      UNION ALL
     SELECT 'txn_$RUN_ID', account_id, 50000, 'USD'
       FROM customers WHERE customer_id = '$CUSTOMER_ID';

UPDATE account_balances SET balance = balance - 50000 WHERE account_id = 'ext_usd';
UPDATE account_balances SET balance = balance + 50000
 WHERE account_id = (SELECT account_id FROM customers WHERE customer_id = '$CUSTOMER_ID');

COMMIT;
SQL
dim "funded 50000 minor units (\$500.00)"

bold "4. Subscribe to webhooks"
ENDPOINT=$(curl -s -X POST "$BASE/v1/webhook_endpoints" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.test/hooks","enabled_events":["*"],"description":"demo"}')
echo "$ENDPOINT"
dim "the secret is shown once here and never again, same as Stripe"

bold "5. Register a card that works"
CARD=$(curl -s -X POST "$BASE/v1/payment_methods" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-pm" \
  -d '{"type":"card","card":{"number":"4242424242424242","exp_month":12,"exp_year":2030}}')
echo "$CARD"
CARD_ID=$(echo "$CARD" | field id)
dim "note what came back: brand and last4 only, the number was never stored"

# ------------------------------------------------------- authorize and capture

bold "6. Start a \$200 payment, holding the funds rather than taking them"
INTENT=$(curl -s -X POST "$BASE/v1/payment_intents" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-pi" \
  -d "{\"amount\":20000,\"currency\":\"usd\",\"customer\":\"$CUSTOMER_ID\",
       \"merchant_account\":\"merch_demo\",\"capture_method\":\"manual\"}")
echo "$INTENT"
INTENT_ID=$(echo "$INTENT" | field id)

bold "7. Confirm it: money leaves the customer, sits in a hold"
curl -s -X POST "$BASE/v1/payment_intents/$INTENT_ID/confirm" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-confirm" \
  -d "{\"payment_method\":\"$CARD_ID\",\"card_number\":\"4242424242424242\"}"
echo
dim "status is requires_capture, amount_capturable is 20000"
dim "the merchant has nothing yet"

bold "8. Capture only \$150 of it"
curl -s -X POST "$BASE/v1/payment_intents/$INTENT_ID/capture" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-capture" \
  -d '{"amount_to_capture":15000}'
echo
dim "the other \$50 went back to the customer in the same transaction"
dim "the hold account is now empty, nothing stranded"

bold "9. Refund \$50 of the capture"
CHARGE_ID=$(curl -s "$BASE/v1/payment_intents/$INTENT_ID/charges" \
  | python -c "import sys,json;print(json.load(sys.stdin)['data'][0]['id'])")
curl -s -X POST "$BASE/v1/refunds" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-refund" \
  -d "{\"charge\":\"$CHARGE_ID\",\"amount\":5000,\"reason\":\"requested_by_customer\"}"
echo

# ------------------------------------------------------------ failure paths

bold "10. Try a card that declines"
DECLINE_PM=$(curl -s -X POST "$BASE/v1/payment_methods" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-pm-bad" \
  -d '{"type":"card","card":{"number":"4000000000009995","exp_month":12,"exp_year":2030}}' \
  | field id)
DECLINE_PI=$(curl -s -X POST "$BASE/v1/payment_intents" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-pi-bad" \
  -d "{\"amount\":1000,\"currency\":\"usd\",\"customer\":\"$CUSTOMER_ID\",
       \"merchant_account\":\"merch_demo\"}" | field id)
curl -s -w "\n-> HTTP %{http_code}\n" -X POST "$BASE/v1/payment_intents/$DECLINE_PI/confirm" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $RUN_ID-confirm-bad" \
  -d "{\"payment_method\":\"$DECLINE_PM\",\"card_number\":\"4000000000009995\"}"
dim "402 with a decline code, not a 500"

bold "11. Send the same request twice with one idempotency key"
KEY="$RUN_ID-twice"
BODY="{\"amount\":700,\"currency\":\"usd\",\"customer\":\"$CUSTOMER_ID\",
       \"merchant_account\":\"merch_demo\"}"
FIRST=$(curl -s -X POST "$BASE/v1/payment_intents" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" -d "$BODY" | field id)
SECOND=$(curl -s -X POST "$BASE/v1/payment_intents" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" -d "$BODY" | field id)
echo "first:  $FIRST"
echo "second: $SECOND"
if [ "$FIRST" = "$SECOND" ]; then
  dim "same object came back, the work ran once"
else
  echo "MISMATCH: two objects were created, which should not happen"
  exit 1
fi

bold "12. Reuse that key with a different amount"
curl -s -w "\n-> HTTP %{http_code}\n" -X POST "$BASE/v1/payment_intents" \
  -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
  -d "{\"amount\":99999,\"currency\":\"usd\",\"customer\":\"$CUSTOMER_ID\",
       \"merchant_account\":\"merch_demo\"}"
dim "422, rather than replaying a response to a question you didn't ask"

# ---------------------------------------------------------------- the books

bold "13. What the ledger says"
docker exec -i "$PG_CONTAINER" psql -U postgres -d stripe_clone -q <<SQL
SELECT a.account_type, b.account_id, b.balance
  FROM account_balances b
  JOIN accounts a ON a.account_id = b.account_id
 WHERE b.balance <> 0
 ORDER BY a.account_type;

SELECT sum(amount) AS "every entry, summed (must be 0)" FROM ledger_entries;
SQL

bold "14. Events produced"
curl -s "$BASE/v1/events?limit=10" \
  | python -c "
import sys, json
for e in json.load(sys.stdin)['data']:
    print(f\"  {e['type']}\")"

bold "15. Health"
HEALTH=$(curl -s "$BASE/actuator/health/ledger")
echo "$HEALTH"
STATUS=$(echo "$HEALTH" | field status)
if [ "$STATUS" = "UP" ]; then
  dim "UP means the entries sum to zero AND the projection agrees with them"
else
  echo
  echo "Health is $STATUS. Something above created or lost money, or the balance"
  echo "projection has drifted from the entries. The entries are the authority."
  exit 1
fi

bold "Done."
echo "Customer:       $CUSTOMER_ID"
echo "Payment intent: $INTENT_ID"
echo "Browse the API: $BASE/docs"
