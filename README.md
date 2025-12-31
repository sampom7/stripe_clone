# Stripe Clone - Bank-Grade Payment Processing System

A production-ready payment processing system built with Spring Boot, implementing double-entry ledger accounting, idempotent APIs, and event-driven architecture using Kafka.

## Architecture

### Core Components

1. **Double-Entry Ledger System**: Ensures financial correctness with balanced debit/credit entries
2. **Idempotent REST APIs**: Prevents duplicate payments using idempotency keys
3. **Event-Driven Architecture**: Kafka-based event processing with exactly-once semantics
4. **Payment Workflows**: Initiation → Authorization → Capture → Settlement

### Technology Stack

- **Language**: Java 17
- **Framework**: Spring Boot 3.2.0
- **Database**: PostgreSQL 15
- **Cache**: Redis 7
- **Messaging**: Apache Kafka
- **Containerization**: Docker & Docker Compose

## Features

### Financial Correctness
- Double-entry bookkeeping ensuring all transactions balance
- Account balance tracking with available/hold balances
- Atomic transaction processing with database transactions

### Idempotency
- Idempotency key support in all payment APIs
- Redis-based caching of responses
- Prevents duplicate processing under retries

### Event-Driven Processing
- Kafka-based event publishing for all payment stages
- Exactly-once processing semantics
- Event consumers for downstream processing

### High Availability
- Optimistic locking with version fields
- Pessimistic locking for account updates
- Retry mechanisms and error handling

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### Setup

1. **Start Infrastructure Services**
   ```bash
   docker-compose up -d
   ```

   This starts:
   - PostgreSQL on port 5432
   - Redis on port 6379
   - Kafka on port 9092
   - Zookeeper on port 2181
   - Kafka UI on port 8081

2. **Initialize Database**
   ```bash
   # The schema will be created automatically by JPA on first run
   # Or manually run the migration script:
   psql -h localhost -U postgres -d stripe_clone -f src/main/resources/db/migration/V1__initial_schema.sql
   ```

3. **Build and Run Application**
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

   The application will start on `http://localhost:8080`

### API Usage

#### Create Payment (Idempotent)

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-key-123" \
  -d '{
    "customerId": "acc_customer_001",
    "merchantId": "acc_merchant_001",
    "amount": 100.00,
    "currency": "USD",
    "paymentMethod": "card",
    "description": "Test payment"
  }'
```

#### Authorize Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/authorize
```

#### Capture Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/capture \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 100.00
  }'
```

#### Settle Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/settle
```

## Payment Flow

1. **Initiation**: Payment request created with idempotency key
2. **Authorization**: Funds held in customer account
3. **Capture**: Funds transferred from customer to merchant (double-entry)
4. **Settlement**: Funds moved to settlement account

## Database Schema

### Accounts
- Tracks account balances (balance, available_balance, hold_balance)
- Supports multiple account types (customer, merchant, settlement, etc.)

### Ledger Transactions & Entries
- Double-entry bookkeeping
- Each transaction has balanced debit/credit entries
- Maintains audit trail

### Payments
- Payment lifecycle tracking
- Idempotency key enforcement
- Links to authorizations, captures, and settlements

## Event Topics

- `payment-events`: All payment lifecycle events
  - PAYMENT_INITIATED
  - PAYMENT_AUTHORIZED
  - PAYMENT_CAPTURED
  - PAYMENT_SETTLED
  - PAYMENT_FAILED

## Configuration

Key configuration in `application.yml`:
- Database connection settings
- Kafka producer/consumer configuration
- Redis connection settings
- Idempotency TTL (default: 24 hours)

## Future Enhancements

- [ ] Dead-letter queue for failed events
- [ ] Retry mechanisms with exponential backoff
- [ ] AWS deployment configuration
- [ ] Centralized logging (CloudWatch/ELK)
- [ ] Microservices decomposition
- [ ] Circuit breakers for external services
- [ ] Rate limiting
- [ ] Webhook support

## Development

### Running Tests
```bash
mvn test
```

### Database Migrations
The project uses JPA auto-DDL. For production, consider Flyway or Liquibase.

### Monitoring
- Health checks: `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/metrics`
- Kafka UI: `http://localhost:8081`

## License

MIT

