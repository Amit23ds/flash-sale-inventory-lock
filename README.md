# Flash Sale Inventory Lock

[![CI](https://github.com/Amit23ds/flash-sale-inventory-lock/actions/workflows/ci.yml/badge.svg)](https://github.com/Amit23ds/flash-sale-inventory-lock/actions/workflows/ci.yml)

A backend service that prevents inventory overselling during high-traffic flash sales — the exact failure mode that hits e-commerce platforms like Flipkart, Amazon, and Swiggy Instamart when thousands of users try to buy the same limited-stock item at once.

Concurrency tests against real MySQL and Redis show that stock never goes negative and every confirmed purchase has exactly one order, with or without the Redis lock. The load tests in [`loadtest/`](loadtest/README.md) can be reproduced from this repo.

## The problem

When a flash sale goes live, hundreds or thousands of users hit "Buy Now" on the same product within milliseconds of each other. Without proper concurrency control, multiple requests can read the same stock count, all see it as available, and all successfully decrement it — resulting in more orders than inventory actually exists.

## Design

Two layers control concurrency. The Redis lock serializes buyers of the same product, and the JPA `@Version` check rejects any stale write that gets past it.

```
Client → PurchaseController → PurchaseServiceImpl ──tryLock──→ Redis (lock "product-lock:{productId}")
                                    │
                                    └─→ PurchaseTransactionalService (@Transactional)
                                          read → check → decrement (@Version) → insert order → commit
                                    │
                              unlock after commit
```

### Layer 1: per-product Redisson lock, taken outside the transaction

- **The lock:** `PurchaseServiceImpl` takes the Redisson lock `product-lock:{productId}` with `tryLock(2, SECONDS)`. No lease time is passed, so Redisson's watchdog keeps renewing the lease while the lock is held.
- **Why outside the transaction:** `PurchaseServiceImpl` isn't transactional. Only `PurchaseTransactionalService.decrementStockAndCreateOrder` is. A request waiting for the lock therefore never holds a database connection, so a queue of buyers on a hot product can't drain the connection pool.
- **Released after commit:** the lock is released in a `finally` block, only after the transactional method has returned and its transaction has committed. If it were released inside the transaction, the next buyer could take the lock and read the stock before the previous decrement was committed. Two buyers would then see the same stock, and the second write would fail its version check.
- **Ownership check:** the lock is only released when `isHeldByCurrentThread()` is true.
- **Lock outcomes:**
  - Not acquired within the 2 s wait: the request fails fast with `429`.
  - Stock is zero once the lock is held: `OutOfStockException` rolls the transaction back and the request returns `409`.

### Layer 2: JPA `@Version` with bounded, jittered retries

`Product` has a `@Version` column, so every stock update is a compare-and-set on the version.

- **Retries:** when a concurrent write wins, the commit throws `ObjectOptimisticLockingFailureException`, and the service retries the whole read-check-decrement-insert transaction.
- **Backoff:** between attempts it sleeps a random 5–25 ms (`app.purchase.retry-backoff-min` / `-max`), so buyers that just collided don't retry in lockstep.
- **Limit:** after `app.purchase.max-optimistic-retries` attempts (default 3) it gives up with `ConcurrentStockUpdateException`, which returns `409`.

While the lock is healthy this layer rarely triggers. It's there for the cases where the lock isn't enough: a lease that expires under load, or degraded mode below.

### Degraded mode: Redis unavailable

- **Lock call fails:** if `tryLock` throws a Redisson `RedisException` (for example `RedisConnectionException` or `RedisTimeoutException`), the service doesn't return `500`. It logs a `WARN` with the `productId` (no stack trace), increments `flashsale.lock.fallback`, and runs the same `@Version` retry path without a lock.
- **What triggers it:** only the `tryLock` call is wrapped. A database exception never triggers degraded mode, and the release step is skipped because no lock was acquired.
- **Unlock fails after commit:** if `unlock()` throws a `RedisException` after the order has committed, the error is logged at `WARN` and the client still gets `200`. Redisson cancels the watchdog renewal even when unlock fails, so the lock key expires on its own within the lock watchdog timeout (30 s by default). Until then, other buyers of that product can get `429`.

**What Redis is needed for:** stock correctness doesn't depend on Redis, because the `@Version` check rejects every stale write whether or not the lock is held (`OptimisticLockingIT` and `FlashSaleRedisOutageIT` show this). Redis is what serves buyers of one product one at a time. Without it they race, and under contention more of them get `409` instead of a confirmed purchase.

## API

**`POST /api/v1/purchase`**

```json
// Request
{ "userId": 1, "productId": 1 }

// Success response
{ "orderId": 1, "userId": 1, "productId": 1, "orderStatus": "CONFIRMED" }
```

| Status | Meaning |
|---|---|
| `200` | Purchase confirmed |
| `404` | Product not found |
| `409` | Out of stock or concurrent stock-update conflict |
| `429` | Lock not acquired — too many concurrent requests, retry |

**`GET /api/v1/stock/{productId}`** — returns current available stock.

## Architecture

```
controller   → handles HTTP requests/responses
service      → business logic, lock acquisition, @Version retries, metrics
repository   → Spring Data JPA, MySQL access
config       → Redisson client and purchase retry settings
exception    → centralized error → HTTP status mapping
```

Error bodies are plain strings. Malformed JSON and non-numeric ids return `400`, unsupported methods `405`, and unknown paths `404`. Unexpected failures are logged at `ERROR` and return `500` with the body `Internal server error`, never the exception message.

## Running locally

```bash
cp .env.example .env   # then edit .env with your own credentials
docker compose up --build
```

This starts the Spring Boot app alongside MySQL, Redis, Prometheus and Grafana as a single reproducible environment. Database credentials are read from `.env`, which is gitignored so secrets never reach the repo.

Set `SPRING_PROFILES_ACTIVE=dev` to turn on SQL and bind-parameter logging (off by default).

## Test strategy

```bash
mvn test        # unit and web-layer tests (no Docker required)
mvn verify      # also runs the Testcontainers integration tests and the coverage gate (requires Docker)
```

| Layer | Test class | What it proves | Tooling |
|---|---|---|---|
| Unit | `PurchaseServiceTest` | Lock lifecycle (acquire, release after success and failure, no release when not acquired), `@Version` retry and exhaustion, degraded mode on `RedisException`, unlock failure after commit still returns the order, and the recorded metrics | JUnit 5, Mockito, Micrometer `SimpleMeterRegistry` |
| Unit | `PurchaseTransactionalServiceTest` | Read-check-decrement-insert: order created when stock is available; `OutOfStockException` and `ProductNotFoundException` otherwise | JUnit 5, Mockito |
| Unit | `ProductServiceTest` | Product lookup and not-found handling | JUnit 5, Mockito |
| Web | `PurchaseControllerTest` | Request validation (`400` field map) and the `200` / `404` / `409` / `429` mappings for purchases | MockMvc, `@MockitoBean`, H2 `test` profile |
| Web | `ProductControllerTest` | Stock endpoint `200` and `404` | MockMvc, `@MockitoBean`, H2 `test` profile |
| Web | `GlobalExceptionHandlerIntegrationTest` | Spring MVC client errors map to `400` / `400` / `405` / `404` instead of `500`, and a `500` never leaks the exception message | MockMvc, `@MockitoBean`, H2 `test` profile |
| Integration | `OptimisticLockingIT` | The `@Version` layer alone prevents oversell: 30 threads call the transactional service directly, with no lock and no retry, and successes = orders = stock sold | Testcontainers MySQL, `RedissonClient` mocked |
| Integration | `FlashSaleConcurrencyIT` | End to end over HTTP with both layers: 50 concurrent purchases against 10 units sell exactly 10, and the rest get `409` / `429` | Testcontainers MySQL + Redis, `TestRestTemplate`, `CountDownLatch` |
| Integration | `FlashSaleRedisOutageIT` | Degraded mode end to end: with Redis paused, 50 concurrent purchases get no `5xx`, no oversell, and one order per `200` | Testcontainers MySQL + Redis, Docker pause/unpause |
| Load | `loadtest/burst.jmx`, `loadtest/sustained.jmx` | Behaviour and throughput under real concurrency, with the stock invariants checked after every run | JMeter, `loadtest/analyze.py` |

## Load testing

Full instructions are in [`loadtest/README.md`](loadtest/README.md). Prerequisites: JMeter 5.6.3 on `PATH`, and the app running via `docker compose`.

- **Burst** (`burst.jmx`) tests correctness under a spike. Every thread sends one purchase for product 1, and a Synchronizing Timer releases all of them at the same instant, so the lock is genuinely contended.
- **Sustained** (`sustained.jmx`) measures throughput. A fixed number of threads loop for a set duration after a ramp-up, buying random products as random users.

```bash
loadtest/run.sh burst                                  # defaults: THREADS=500, STOCK=100
loadtest/run.sh sustained                              # defaults: THREADS=50, DURATION=60
THREADS=100 PRODUCT_COUNT=10 DURATION=120 loadtest/run.sh sustained
```

`run.sh` does the following:

1. Checks that `/actuator/health` is `UP`.
2. Resets the data. It deletes all orders and products in the compose MySQL.
3. Runs JMeter.
4. Reads the final stock and order count.
5. Runs `analyze.py`, which prints response codes, latency percentiles, throughput, peak in-flight requests and Little's-law concurrency. It exits non-zero if `finalStock >= 0`, `orders == initialStock - finalStock`, `count(200) == orders`, or "no unexpected codes" fails.

Discard a warm-up run, then run each scenario 3 times and report the median.

| Metric | Burst | Sustained |
|---|---|---|
| Threads | <!-- FILL: burst threads --> | <!-- FILL: sustained threads --> |
| Stock per product | <!-- FILL: burst stock --> | <!-- FILL: sustained stock --> |
| Products | <!-- FILL: burst products --> | <!-- FILL: sustained products --> |
| Confirmed (200) | <!-- FILL: burst confirmed --> | <!-- FILL: sustained confirmed --> |
| 409s | <!-- FILL: burst 409s --> | <!-- FILL: sustained 409s --> |
| 429s | <!-- FILL: burst 429s --> | <!-- FILL: sustained 429s --> |
| Unexpected codes | <!-- FILL: burst unexpected --> | <!-- FILL: sustained unexpected --> |
| Final stock | <!-- FILL: burst final stock --> | <!-- FILL: sustained final stock --> |
| Peak in-flight requests | <!-- FILL: burst peak in-flight --> | <!-- FILL: sustained peak in-flight --> |
| Throughput (req/s) | <!-- FILL: burst throughput --> | <!-- FILL: sustained throughput --> |
| p50 latency (ms) | <!-- FILL: burst p50 --> | <!-- FILL: sustained p50 --> |
| p95 latency (ms) | <!-- FILL: burst p95 --> | <!-- FILL: sustained p95 --> |
| p99 latency (ms) | <!-- FILL: burst p99 --> | <!-- FILL: sustained p99 --> |

Machine: <!-- FILL: machine spec (CPU, memory, Docker resources, JMeter on the same host or not) -->

## Observability

`docker compose up --build` also starts Prometheus (http://localhost:9090) and Grafana (http://localhost:3000, anonymous read-only access). Prometheus scrapes `app:8080/actuator/prometheus`, and Grafana opens on the provisioned **Flash Sale** dashboard. The app exposes only the `health`, `info`, `metrics` and `prometheus` actuator endpoints, and every metric carries the tag `application=flash-sale`.

| Metric | Type and tags | Question it answers |
| --- | --- | --- |
| `flashsale.purchase` | Counter, `outcome` = `confirmed` / `out_of_stock` / `not_found` / `lock_timeout` / `version_conflict` / `error` | What happens to purchase attempts? Is demand being turned away by stock, by lock contention, or by failures? |
| `flashsale.lock.wait` | Timer (histogram), `result` = `acquired` / `timeout` / `redis_error` | How long do buyers queue for the per-product Redis lock, and how often do they give up or hit a Redis failure? |
| `flashsale.lock.held` | Timer (histogram) | How long is the lock held, from acquisition to release? This bounds per-product throughput. |
| `flashsale.optimistic.retry` | Counter | How often does the `@Version` check force a retry? Rising values mean writes are racing (lock lease expiry or degraded mode). |
| `flashsale.lock.fallback` | Counter | How many purchases ran in degraded mode because Redis was unreachable? Any non-zero value means the lock layer is down. |
| `http.server.requests` (`uri="/api/v1/purchase"`) | Timer (histogram), by `status` | What latency do clients see for each response status? |
| `hikaricp.connections.active` / `.pending` | Gauges | Is the database connection pool the bottleneck? Pending connections mean requests are waiting for a connection. |

Metrics are never tagged with `productId` or `userId`, which would create one time series per product or user.

<!-- FILL: Grafana screenshot -->

## CI and coverage

GitHub Actions ([`ci.yml`](.github/workflows/ci.yml)) runs on every push to `main` and every pull request against `main`:

1. `mvn -B verify` on JDK 25 runs the unit and web-layer tests (Surefire), then the Testcontainers integration tests (Failsafe).
2. JaCoCo merges unit and integration coverage into one report and **fails the build** if merged line coverage falls below the gate (<!-- FILL: coverage % -->). Only the `DemoApplication` bootstrap class is excluded. Lombok-generated code is ignored through `lombok.config`.
3. The JaCoCo, Surefire and Failsafe reports are uploaded as build artifacts, even when the build fails.
4. A table of test counts and line/branch coverage is written to the run summary.

## Known limitations

- **One lock per hot product serializes its buyers.** All purchases of one product queue on a single Redis lock, so that product's throughput ceiling is roughly 1 / (lock-held time), however many app instances run. `flashsale.lock.held` measures that time. Spreading stock across lock shards, or reserving it in Redis, would raise the ceiling at the cost of complexity.
- **Load-test results come from a single laptop.** JMeter, the app, MySQL and Redis all run on the same machine, so they compete for CPU, and nothing crosses a real network. Treat the numbers as relative comparisons between runs, not as capacity figures for production.

## Tech stack

**Core:** Java 25, Spring Boot 3, Spring Data JPA
**Concurrency & caching:** Redis, Redisson (distributed locking), JPA optimistic locking
**Persistence:** MySQL
**Observability:** Spring Boot Actuator, Micrometer, Prometheus, Grafana
**Testing:** JUnit 5, Mockito, Testcontainers, JaCoCo, Apache JMeter
**Infra:** Docker, Docker Compose, GitHub Actions

## What this project demonstrates

- Distributed locking with Redisson, including lock release after commit in a `finally` block and ownership checks via `isHeldByCurrentThread()`
- Two-layer concurrency control: the Redis lock serializes buyers, and JPA `@Version` keeps stock correct even when the lock layer fails
- Graceful degradation: a Redis outage turns into more `409`s, not into `500`s
- The distinction between database transactions (atomic writes) and distributed locks (cross-instance coordination), and why both are needed together
- Fail-fast design under load instead of unbounded request queuing
- Reproducible, genuinely concurrent load testing with automated invariant checks
- Testcontainers integration tests that prove zero oversell with both layers, with `@Version` alone, and with Redis paused

## Author

Amit Kumar Yadav
