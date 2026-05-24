# Idempotency Gateway — The "Pay-Once" Protocol

> A production-ready RESTful middleware service built for **IgirePay Technologies Ltd.** that guarantees every payment is processed **exactly once**, no matter how many times the client retries.

---

## Table of Contents

1. [Architecture Diagram](#architecture-diagram)
2. [Setup Instructions](#setup-instructions)
3. [API Documentation](#api-documentation)
4. [Design Decisions](#design-decisions)
5. [Developer's Choice Feature](#developers-choice-feature)

---

## Architecture Diagram

The sequence below shows all three acceptance scenarios plus the bonus in-flight race-condition guard.

```
Client                        Idempotency Gateway                 Payment Processor
  |                                    |                                  |
  |── POST /process-payment ──────────>|                                  |
  |   Idempotency-Key: KEY-A           |                                  |
  |   {"amount":100,"currency":"RWF"}  |                                  |
  |                                    |                                  |
  |                        ┌───────────┴───────────┐                     |
  |                        │  Key in MySQL/memory?  │                     |
  |                        └───────────┬───────────┘                     |
  |                                    │                                  |
  |                        ┌───────────▼───────────┐                     |
  |         ┌──── NO ──────│   Insert KEY-A         │                     |
  |         │              │   State: IN_FLIGHT      │                     |
  |         │              │   Saved to MySQL        │                     |
  |         │              └───────────────────────┘                     |
  |         │                                                             |
  |         │                          │──── process(request) ──────────>|
  |         │                          │<─── PaymentResponse ────────────|
  |         │                          │                                  |
  |         │              ┌───────────▼───────────┐                     |
  |         │              │  Update MySQL record   │                     |
  |         │              │  State: COMPLETED      │                     |
  |         │              └───────────────────────┘                     |
  |         │                                                             |
  |<── 201 Created ────────────────────|                                  |
  |   {"status":"SUCCESS","message":   |                                  |
  |    "Charged 100 RWF", ...}         |                                  |
  |                                    |                                  |
  |  ── SCENARIO A: Duplicate ─────────────────────────────────────────  |
  |                                    |                                  |
  |── POST /process-payment ──────────>|                                  |
  |   Idempotency-Key: KEY-A (SAME)    |                                  |
  |   {"amount":100,"currency":"RWF"}  |                                  |
  |                        ┌───────────▼───────────┐                     |
  |                        │  Key EXISTS in MySQL   │                     |
  |                        │  Hash matches          │                     |
  |                        │  State: COMPLETED      │                     |
  |                        └───────────────────────┘                     |
  |                                    │   (no payment processor call)    |
  |<── 201 Created ────────────────────|                                  |
  |   X-Cache-Hit: true                |                                  |
  |   {same body as first response}    |                                  |
  |                                    |                                  |
  |  ── SCENARIO B: Body Mismatch ─────────────────────────────────────  |
  |                                    |                                  |
  |── POST /process-payment ──────────>|                                  |
  |   Idempotency-Key: KEY-A (SAME)    |                                  |
  |   {"amount":500,"currency":"RWF"}  |                                  |
  |                        ┌───────────▼───────────┐                     |
  |                        │  Key EXISTS            │                     |
  |                        │  Hash MISMATCH         │                     |
  |                        └───────────────────────┘                     |
  |<── 409 Conflict ───────────────────|                                  |
  |   "Idempotency key already used    |                                  |
  |    for a different request body."  |                                  |
  |                                    |                                  |
  |  ── SCENARIO C: In-Flight Race ────────────────────────────────────  |
  |                                    |                                  |
  |── Request A (KEY-B) ──────────────>|──── process(request) ──────────>|
  |                                    │     [2 sec delay ...]            |
  |── Request B (KEY-B, duplicate) ───>|                                  |
  |                        ┌───────────▼───────────┐                     |
  |                        │  Key EXISTS            │                     |
  |                        │  State: IN_FLIGHT      │                     |
  |                        │  → awaitCompletion()   │                     |
  |                        └───────────────────────┘                     |
  |                                    │<── PaymentResponse ─────────────|
  |                                    │    (Request A finishes)          |
  |<── 201 Created (Request A) ────────|                                  |
  |<── 201 Created (Request B) ────────|  (same result, X-Cache-Hit:true) |
```

---

## Setup Instructions

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 17 or higher |
| Maven | 3.8+ |
| MySQL | 8.0+ |

### MySQL Setup

1. Make sure MySQL is running on your machine
2. Open `src/main/resources/application.properties`
3. Update your credentials:

```properties
spring.datasource.username=root
spring.datasource.password=yourpassword
```

> The database `idempotency_db` is created **automatically** on first run — no SQL script needed.

### Clone and Run

```bash
# 1. Clone the repository
git clone https://github.com/Emelyne749/SheCanCode-associate-Assessment-.git
cd idempotency-gateway

# 2. Build the project
mvn clean install -DskipTests

# 3. Start the server
mvn spring-boot:run
```

The server starts on **http://localhost:8080**.

### Run Tests

```bash
mvn test
```

> ⚠️ Integration tests include the 2-second payment simulation delay. Running all tests takes ~15 seconds.

---

## API Documentation

### `POST /process-payment`

Submits a payment request. The idempotency layer guarantees the payment is processed at most once per unique `Idempotency-Key`.

#### Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | Must be `application/json` |
| `Idempotency-Key` | Yes | A unique client-generated string (e.g. UUID). Reusing this key with the **same body** returns the cached response. Reusing it with a **different body** returns 409. |

#### Request Body

```json
{
  "amount": 100,
  "currency": "RWF"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `amount` | number | Yes | Must be > 0 |
| `currency` | string | Yes | Non-blank (e.g. "RWF", "USD") |

#### Responses

##### `201 Created` — New Payment Processed

```json
{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A3F91C204B8D",
  "processedAt": "2025-06-12T10:23:45.123Z"
}
```

##### `201 Created` — Duplicate (Cache Hit)

Same body as the original 201. Includes the extra header:

```
X-Cache-Hit: true
```

##### `409 Conflict` — Same Key, Different Body

```json
{
  "statusCode": 409,
  "error": "Conflict",
  "message": "Idempotency key already used for a different request body.",
  "timestamp": "2025-06-12T10:24:00.456Z"
}
```

##### `400 Bad Request` — Missing Header or Invalid Body

```json
{
  "statusCode": 400,
  "error": "Bad Request",
  "message": "Missing required header: Idempotency-Key"
}
```

#### Full cURL Examples

**First request:**
```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-order-id-001" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Duplicate request (same key, same body):**
```bash
curl -v -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-order-id-001" \
  -d '{"amount": 100, "currency": "RWF"}'
# Look for: X-Cache-Hit: true in response headers
```

**Body mismatch (same key, different amount):**
```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-order-id-001" \
  -d '{"amount": 500, "currency": "RWF"}'
# Returns: 409 Conflict
```

### `GET /actuator/health`

```json
{
  "status": "UP"
}
```

---

## Design Decisions

### 1. MySQL-Backed Persistence with JPA

Idempotency records are persisted to a MySQL database using Spring Data JPA. This means:
- Records survive server restarts — a duplicate request after a restart still returns the cached response
- The `idempotency_records` table is auto-created by Hibernate on startup (`ddl-auto=update`)
- A unique index on `idempotency_key` enforces integrity at the database level

### 2. Dual-Layer Store (MySQL + ConcurrentHashMap)

The store uses two layers working together:
- **In-memory ConcurrentHashMap** — handles `IN_FLIGHT` state and concurrent duplicate detection within the same server instance. `putIfAbsent()` is atomic, eliminating check-then-act race conditions.
- **MySQL** — provides durable persistence. On every completed payment, the result is written to MySQL so it survives restarts.

When a request arrives, the in-memory layer is checked first (fast path), then MySQL (covers restart scenarios).

### 3. SHA-256 Request Fingerprinting

Rather than storing the raw request body, a **SHA-256 hash** of the canonicalized JSON is stored. This:
- Reduces storage usage
- Provides a deterministic, constant-size comparison value
- Uses sorted JSON serialization so `{"a":1,"b":2}` and `{"b":2,"a":1}` produce the same hash

### 4. In-Flight State + `synchronized` Monitor (Bonus Race Condition)

Each `IdempotencyRecord` has a `state` field: `IN_FLIGHT` or `COMPLETED`. When a duplicate arrives while the original is still processing, the duplicate thread calls `awaitCompletion()` — which uses Java's built-in `Object.wait()/notifyAll()` to block until the original finishes, then returns the same result with `X-Cache-Hit: true`.

### 5. Immutable `PaymentRequest` with Value Equality

`PaymentRequest` is immutable with `equals()` based on amount + currency, making it safe to compare across threads.

---

## Developer's Choice Feature

### TTL-Based Automatic Eviction

**What it is:** Idempotency records are automatically deleted from MySQL after a configurable TTL (default: **24 hours**). A Spring `@Scheduled` background job runs every hour to purge expired entries from both MySQL and the in-memory map.

**Why it matters in a real Fintech system:**

Without automatic eviction, the `idempotency_records` table grows indefinitely — causing slow queries and storage exhaustion in production. The 24-hour TTL aligns with the industry-standard retry window. After 24 hours, a retry is almost certainly a business error rather than a legitimate network retry.

**Configuration** (in `application.properties`):

```properties
# How long to keep idempotency records (in hours)
idempotency.ttl-hours=24

# How often to run the cleanup job (in milliseconds)
idempotency.cleanup-interval-ms=3600000
```

Both values are tunable without code changes, making the service easy to operate in different environments.

---

## Project Structure

```
src/
├── main/java/com/igirepay/gateway/
│   ├── IdempotencyGatewayApplication.java
│   ├── config/
│   │   └── AppConfig.java                    # Enables @Scheduled
│   ├── controller/
│   │   ├── PaymentController.java            # POST /process-payment
│   │   └── GlobalExceptionHandler.java       # Centralized error mapping
│   ├── entity/
│   │   └── IdempotencyRecordEntity.java      # JPA entity → MySQL table
│   ├── model/
│   │   ├── PaymentRequest.java               # Request body (immutable)
│   │   ├── PaymentResponse.java              # Response body
│   │   ├── IdempotencyRecord.java            # In-memory state machine
│   │   └── ErrorResponse.java               # Standard error body
│   ├── repository/
│   │   └── IdempotencyRepository.java        # Spring Data JPA repository
│   └── service/
│       ├── IdempotencyService.java           # Core idempotency logic
│       ├── IdempotencyStore.java             # Dual-layer store (MySQL + memory)
│       ├── PaymentService.java               # Payment simulation
│       └── HashService.java                  # SHA-256 body fingerprinting
└── test/java/com/igirepay/gateway/
    └── IdempotencyGatewayIntegrationTest.java
```

---

*Built for IgirePay Technologies Ltd. — Pay-Once Protocol challenge.*