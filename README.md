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
  |                        │  Key in store?         │                     |
  |                        └───────────┬───────────┘                     |
  |                                    │                                  |
  |                        ┌───────────▼───────────┐                     |
  |         ┌──── NO ──────│   Insert KEY-A         │                     |
  |         │              │   State: IN_FLIGHT      │                     |
  |         │              └───────────────────────┘                     |
  |         │                                                             |
  |         │                          │──── process(request) ──────────>|
  |         │                          │<─── PaymentResponse ────────────|
  |         │                          │                                  |
  |         │              ┌───────────▼───────────┐                     |
  |         │              │  Store response        │                     |
  |         │              │  State: COMPLETED      │                     |
  |         │              └───────────────────────┘                     |
  |         │                                                             |
  |<── 201 Created ────────────────────|                                  |
  |   {"status":"SUCCESS","message":   |                                  |
  |    "Charged 100 RWF", ...}         |                                  |
  |                                    |                                  |
  |                                    |                                  |
  |  ── SCENARIO A: Duplicate ──────────────────────────────────────── ──|
  |                                    |                                  |
  |── POST /process-payment ──────────>|                                  |
  |   Idempotency-Key: KEY-A (SAME)    |                                  |
  |   {"amount":100,"currency":"RWF"}  |                                  |
  |                                    │                                  |
  |                        ┌───────────▼───────────┐                     |
  |                        │  Key EXISTS            │                     |
  |                        │  Hash matches          │                     |
  |                        │  State: COMPLETED      │                     |
  |                        └───────────────────────┘                     |
  |                                    │   (no payment processor call)    |
  |<── 201 Created ────────────────────|                                  |
  |   X-Cache-Hit: true                |                                  |
  |   {same body as first response}    |                                  |
  |                                    |                                  |
  |  ── SCENARIO B: Body Mismatch ──────────────────────────────────── ──|
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
  |  ── SCENARIO C: In-Flight Race ─────────────────────────────────── ──|
  |                                    |                                  |
  |── Request A (KEY-B) ──────────────>|──── process(request) ──────────>|
  |                                    │     [2 sec delay ...]            |
  |── Request B (KEY-B, duplicate) ───>|                                  |
  |                        ┌───────────▼───────────┐                     |
  |                        │  Key EXISTS            │                     |
  |                        │  State: IN_FLIGHT       │                     |
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

> No database setup required. The service uses an in-memory `ConcurrentHashMap` as its idempotency store.

### Clone and Run

```bash
# 1. Clone the repository
git clone https://github.com/<your-username>/idempotency-gateway.git
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

---

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

---

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

---

### `GET /actuator/health`

Returns the health status of the service.

```json
{
  "status": "UP"
}
```

---

## Design Decisions

### 1. In-Memory Store (`ConcurrentHashMap`)

The idempotency store uses a `ConcurrentHashMap<String, IdempotencyRecord>`. This was chosen to keep the service dependency-free (no Redis or database needed to run) while providing the thread-safety guarantees required for correct concurrent behavior.

**Key operation:** `putIfAbsent(key, record)` is atomic — it either inserts and returns `null` (first request), or returns the existing record (duplicate). This single atomic call eliminates the classic check-then-act race condition.

### 2. SHA-256 Request Fingerprinting

Rather than storing the raw request body, a **SHA-256 hash** of the canonicalized JSON is stored. This:
- Reduces memory usage for large bodies
- Provides a deterministic, constant-size comparison value
- Uses sorted JSON serialization so `{"a":1,"b":2}` and `{"b":2,"a":1}` produce the same hash

### 3. In-Flight State + `synchronized` Monitor (Bonus Race Condition)

Each `IdempotencyRecord` has a `state` field: `IN_FLIGHT` or `COMPLETED`. When a duplicate arrives while the original is still processing, instead of returning an error, the duplicate thread calls `awaitCompletion()` — which uses Java's built-in `Object.wait()/notifyAll()` to block efficiently until the original finishes. The duplicate then returns the original's result with `X-Cache-Hit: true`.

This eliminates the double-processing race without requiring any external coordination mechanism.

### 4. Immutable `PaymentRequest` with Value Equality

`PaymentRequest` is an immutable record with `equals()` based on amount + currency. This makes it safe to compare across threads and easy to reason about.

---

## Developer's Choice Feature

### TTL-Based Automatic Eviction

**What it is:** Idempotency records are automatically removed from memory after a configurable TTL (default: **24 hours**). A Spring `@Scheduled` background job runs every hour to purge expired entries.

**Why it matters in a real Fintech system:**

In production, a payment gateway processes thousands of transactions per day. Without automatic eviction, the idempotency store would grow indefinitely and eventually exhaust heap memory — causing an OutOfMemoryError that would crash the service.

The 24-hour TTL is not arbitrary. It aligns with the industry-standard window during which clients are expected to retry a timed-out request. After 24 hours, a retry would almost certainly be a business error (not a legitimate retry), so it is correct to treat it as a new request.

**Configuration** (in `application.properties`):

```properties
# How long to keep idempotency records (in hours)
idempotency.ttl-hours=24

# How often to run the cleanup job (in milliseconds)
idempotency.cleanup-interval-ms=3600000
```

Both values are tunable without code changes, making the service easy to operate in different environments (e.g., a shorter TTL for a high-throughput staging environment).

---

## Project Structure

```
src/
├── main/java/com/igirepay/gateway/
│   ├── IdempotencyGatewayApplication.java   # Entry point
│   ├── config/
│   │   └── AppConfig.java                   # Enables @Scheduled
│   ├── controller/
│   │   ├── PaymentController.java           # POST /process-payment
│   │   └── GlobalExceptionHandler.java      # Centralized error mapping
│   ├── model/
│   │   ├── PaymentRequest.java              # Request body (immutable)
│   │   ├── PaymentResponse.java             # Response body
│   │   ├── IdempotencyRecord.java           # Store entry + state machine
│   │   └── ErrorResponse.java              # Standard error body
│   └── service/
│       ├── IdempotencyService.java          # Core idempotency logic
│       ├── IdempotencyStore.java            # Thread-safe store + TTL eviction
│       ├── PaymentService.java              # Payment simulation
│       └── HashService.java                 # SHA-256 body fingerprinting
└── test/java/com/igirepay/gateway/
    └── IdempotencyGatewayIntegrationTest.java  # Integration tests
```
