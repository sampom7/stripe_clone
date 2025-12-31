# Quick Start Guide

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

## Step 1: Start Infrastructure

```bash
docker-compose up -d
```

Wait for all services to be healthy (about 30-60 seconds). Verify with:

```bash
docker-compose ps
```

All services should show "healthy" status.

## Step 2: Build Application

```bash
mvn clean install
```

## Step 3: Run Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

## Step 4: Create Test Accounts

First, create accounts for testing:

```bash
# Create customer account with $10,000
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountType": "CUSTOMER_WALLET",
    "currency": "USD",
    "initialBalance": 10000.00
  }'

# Create merchant account
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountType": "MERCHANT_ACCOUNT",
    "currency": "USD",
    "initialBalance": 0.00
  }'

# Create settlement account
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountType": "SETTLEMENT_ACCOUNT",
    "currency": "USD",
    "initialBalance": 0.00
  }'
```

Note the `external_id` from each response. You'll need these for payment creation.

## Step 5: Process a Payment

### 5.1 Initiate Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-payment-001" \
  -d '{
    "customerId": "acc_customer_001",
    "merchantId": "acc_merchant_001",
    "amount": 100.00,
    "currency": "USD",
    "paymentMethod": "card",
    "description": "Test payment"
  }'
```

Save the `paymentId` from the response.

### 5.2 Authorize Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/authorize
```

Replace `{paymentId}` with the ID from step 5.1.

### 5.3 Capture Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/capture \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 100.00
  }'
```

### 5.4 Settle Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/settle
```

## Step 6: Verify Results

### Check Payment Status

```bash
curl http://localhost:8080/api/v1/payments/{paymentId}
```

### Check Account Balances

```bash
# Check customer account
curl http://localhost:8080/api/v1/accounts/acc_customer_001

# Check merchant account
curl http://localhost:8080/api/v1/accounts/acc_merchant_001

# Check settlement account
curl http://localhost:8080/api/v1/accounts/settlement_account_USD
```

## Testing Idempotency

Try sending the same payment request twice with the same idempotency key:

```bash
# First request
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idempotent-test-001" \
  -d '{
    "customerId": "acc_customer_001",
    "merchantId": "acc_merchant_001",
    "amount": 50.00,
    "currency": "USD",
    "paymentMethod": "card",
    "description": "Idempotency test"
  }'

# Second request (same idempotency key) - should return cached response
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idempotent-test-001" \
  -d '{
    "customerId": "acc_customer_001",
    "merchantId": "acc_merchant_001",
    "amount": 50.00,
    "currency": "USD",
    "paymentMethod": "card",
    "description": "Idempotency test"
  }'
```

Both requests should return the same `paymentId`.

## Monitoring

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

### Kafka UI
Open `http://localhost:8081` in your browser to view Kafka topics and messages.

### Logs
Application logs are written to:
- `logs/stripe-clone.log` - General application logs
- `logs/payments.log` - Payment-specific logs

## Troubleshooting

### Services not starting
```bash
# Check service logs
docker-compose logs postgres
docker-compose logs kafka
docker-compose logs redis

# Restart services
docker-compose restart
```

### Database connection issues
Ensure PostgreSQL is running and accessible:
```bash
docker-compose ps postgres
```

### Kafka connection issues
Check Kafka is running:
```bash
docker-compose ps kafka
```

Verify Kafka topics:
```bash
# Using Kafka UI at http://localhost:8081
# Or using kafka CLI (if installed)
docker exec -it stripe-clone-kafka kafka-topics --list --bootstrap-server localhost:9092
```

## Next Steps

1. Review `ARCHITECTURE.md` for system design details
2. Explore the codebase structure
3. Add more payment methods
4. Implement refunds
5. Add webhook support
6. Deploy to AWS

