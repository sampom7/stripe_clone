# Architecture Documentation

## System Overview

This is a bank-grade payment processing system implementing a double-entry ledger with event-driven architecture. The system is designed as a monolith first, with clear boundaries for future microservices decomposition.

## Core Principles

### 1. Financial Correctness
- **Double-Entry Bookkeeping**: Every transaction has balanced debits and credits
- **Atomic Operations**: Database transactions ensure all-or-nothing execution
- **Balance Tracking**: Separate tracking of balance, available balance, and hold balance

### 2. Idempotency
- **Idempotency Keys**: All payment APIs require idempotency keys
- **Redis Caching**: Responses cached in Redis to prevent duplicate processing
- **Processing Locks**: Prevents concurrent processing of the same idempotency key

### 3. Exactly-Once Processing
- **Kafka Transactions**: Idempotent producers with transactional semantics
- **Manual Commit**: Consumers manually acknowledge messages after processing
- **Read Committed**: Isolation level ensures no dirty reads

### 4. High Availability
- **Optimistic Locking**: Version fields prevent lost updates
- **Pessimistic Locking**: Account updates use row-level locks
- **Retry Mechanisms**: Exponential backoff for transient failures
- **Dead-Letter Queues**: Failed messages sent to DLQ for manual review

## Architecture Layers

### 1. API Layer (`controller`)
- RESTful endpoints with validation
- Idempotency key handling
- Request/response transformation

### 2. Service Layer (`service`)
- Business logic orchestration
- Transaction management
- Event publishing

### 3. Domain Layer (`model`)
- Entity definitions
- Business rules
- State management

### 4. Infrastructure Layer (`config`, `repository`)
- Database access
- Kafka configuration
- Redis configuration
- External service integration

## Payment Flow

```
1. Initiation
   ├─ Client sends payment request with idempotency key
   ├─ System checks idempotency cache
   ├─ Creates Payment entity (PENDING)
   └─ Publishes PAYMENT_INITIATED event

2. Authorization
   ├─ System holds funds in customer account
   ├─ Creates Authorization entity (APPROVED)
   ├─ Updates Payment status (AUTHORIZED)
   └─ Publishes PAYMENT_AUTHORIZED event

3. Capture
   ├─ System completes hold (debits customer)
   ├─ Creates double-entry transaction (debit customer, credit merchant)
   ├─ Creates Capture entity (COMPLETED)
   ├─ Updates Payment status (CAPTURED)
   └─ Publishes PAYMENT_CAPTURED event

4. Settlement
   ├─ System creates double-entry transaction (debit merchant, credit settlement)
   ├─ Creates Settlement entity (COMPLETED)
   └─ Publishes PAYMENT_SETTLED event
```

## Double-Entry Ledger

### Account Types
- **CUSTOMER_WALLET**: Customer funds
- **MERCHANT_ACCOUNT**: Merchant receivables
- **SETTLEMENT_ACCOUNT**: Funds ready for bank transfer
- **FEE_ACCOUNT**: Platform fees
- **PLATFORM_RESERVE**: Reserve funds
- **REFUND_ACCOUNT**: Refund processing

### Transaction Types
- **PAYMENT**: Customer payment
- **AUTHORIZATION**: Fund hold
- **CAPTURE**: Fund transfer
- **REFUND**: Payment reversal
- **SETTLEMENT**: Bank transfer
- **FEE**: Fee collection
- **ADJUSTMENT**: Manual adjustments

### Balance Management
- **balance**: Total account balance
- **available_balance**: Funds available for use
- **hold_balance**: Funds held (authorized but not captured)

## Event-Driven Architecture

### Event Topics
- `payment-events`: All payment lifecycle events
- `dlq-payment-events`: Failed events for manual review

### Event Types
- `PAYMENT_INITIATED`: Payment request created
- `PAYMENT_AUTHORIZED`: Funds authorized
- `PAYMENT_CAPTURED`: Funds captured
- `PAYMENT_SETTLED`: Funds settled
- `PAYMENT_FAILED`: Payment failed at any stage

### Exactly-Once Semantics
1. **Producer**: Idempotent producer with `enable.idempotence=true`
2. **Consumer**: Manual acknowledgment after processing
3. **Transactions**: Kafka transactions for atomicity
4. **Isolation**: `read_committed` isolation level

## Database Schema

### Accounts
Tracks account balances and status. Uses pessimistic locking for updates.

### Ledger Transactions
Represents a financial transaction. Must have balanced entries.

### Ledger Entries
Individual debit/credit entries. Links to accounts and transactions.

### Payments
Payment lifecycle tracking with idempotency enforcement.

### Authorizations, Captures, Settlements
Track payment workflow stages.

## Idempotency Implementation

### Flow
1. Client sends request with `Idempotency-Key` header
2. System checks Redis cache for existing response
3. If cached, return cached response
4. If not cached, check if processing (Redis lock)
5. If processing, return 409 Conflict
6. If not processing, mark as processing and execute
7. Cache response in Redis (TTL: 24 hours)
8. Clear processing lock

### Redis Keys
- `idempotency:{key}`: Cached response
- `idempotency:processing:{key}`: Processing lock

## Error Handling

### Retry Logic
- Exponential backoff: `backoff * 2^(attempt-1)`
- Max attempts: 3 (configurable)
- Retryable exceptions: Transient failures

### Dead-Letter Queue
- Failed events after max retries sent to DLQ
- DLQ topic: `dlq-{original-topic}`
- DLQ consumer logs for manual review

## Concurrency Control

### Optimistic Locking
- Version fields on entities
- JPA `@Version` annotation
- Prevents lost updates

### Pessimistic Locking
- Row-level locks on account updates
- `PESSIMISTIC_WRITE` lock mode
- Prevents concurrent balance updates

## Monitoring & Observability

### Logging
- Structured logging with MDC
- Trace IDs for request correlation
- Separate log files for payments
- Log rotation (100MB files, 30 days retention)

### Metrics
- Spring Actuator endpoints
- Health checks
- Custom business metrics (future)

## Future Microservices Decomposition

### Potential Services
1. **Payment Service**: Payment initiation and workflow
2. **Ledger Service**: Double-entry accounting
3. **Authorization Service**: Payment authorization
4. **Settlement Service**: Bank settlement
5. **Account Service**: Account management

### Decomposition Strategy
1. Identify service boundaries
2. Extract shared database to service-specific databases
3. Use events for inter-service communication
4. Implement API Gateway
5. Service discovery and configuration

## Security Considerations

### Current (Basic)
- Input validation
- SQL injection prevention (JPA)
- XSS prevention (framework defaults)

### Future Enhancements
- API authentication (JWT/OAuth2)
- Rate limiting
- Encryption at rest
- PCI-DSS compliance
- Audit logging

## Performance Considerations

### Database
- Indexed foreign keys
- Connection pooling (HikariCP)
- Batch operations
- Query optimization

### Caching
- Redis for idempotency
- Future: Cache account balances
- Future: Cache payment status

### Kafka
- Partitioning for parallelism
- Consumer groups for scaling
- Batch processing

## Deployment

### Local Development
- Docker Compose for infrastructure
- JPA auto-DDL for schema
- Embedded services

### Production (Future)
- AWS ECS/EKS for containers
- RDS for PostgreSQL
- ElastiCache for Redis
- MSK for Kafka
- CloudWatch for logging
- ALB for load balancing

