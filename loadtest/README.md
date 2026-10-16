# Load tests

Two JMeter scenarios against `POST /api/v1/purchase`:

| Scenario | Question it answers | Shape |
|---|---|---|
| `burst.jmx` | Does stock stay correct under a true spike? | `threads` buyers, ramp-up 0, one purchase each, all released together by a Synchronizing Timer (10 s timeout), all on product 1 |
| `sustained.jmx` | How much throughput does the service sustain? | `threads` buyers looping for `duration` seconds after a `rampup`, random user id, random product in `1..productCount` |

Both plans accept `200`, `409` and `429` as expected outcomes, so only unexpected codes (5xx, 400, connection errors) count as errors.

## Prerequisites

- **JMeter 5.6.3** on `PATH` (`jmeter --version`).
- **Python 3** (the analyzer uses only the standard library).
- **The app running via docker compose** from the repo root, with `.env` in place:
  ```bash
  cp .env.example .env          # first time only
  docker compose up --build -d
  curl localhost:8080/actuator/health   # must report "UP"
  ```

> **`run.sh` deletes every order and product** in the compose MySQL before each run. Do not point it at data you want to keep.

## Running

From the repo root:

```bash
loadtest/run.sh burst                                  # 500 threads, 100 units of product 1
THREADS=1000 STOCK=50 loadtest/run.sh burst

loadtest/run.sh sustained                              # 50 threads, 10 s ramp-up, 60 s, 1 product
THREADS=100 PRODUCT_COUNT=10 DURATION=120 loadtest/run.sh sustained
```

| Variable | Burst default | Sustained default | Meaning |
|---|---|---|---|
| `THREADS` | 500 | 50 | JMeter threads (concurrent buyers) |
| `STOCK` | 100 | 1000000 | Units seeded per product. The sustained default is large so the run measures purchases rather than a sold-out fast path. |
| `PRODUCT_COUNT` | 1 | 1 | Products seeded, ids `1..N`. Burst always targets product 1. |
| `DURATION` | n/a | 60 | Sustained run length in seconds |
| `RAMPUP` | n/a | 10 | Sustained ramp-up in seconds |
| `HOST` / `PORT` | localhost / 8080 | localhost / 8080 | Application address |

Each run does the following:

1. Exits unless `/actuator/health` is `UP`.
2. Resets the data (`PRODUCT_COUNT` products × `STOCK` units, version 0).
3. Runs JMeter in non-GUI mode.
4. Reads the final total stock and order count from MySQL.
5. Runs `analyze.py`. The script exits non-zero if any invariant fails.

Output goes to `loadtest/results/<scenario>-<timestamp>/` (gitignored):

- `results.jtl`: raw samples (CSV)
- `report/index.html`: JMeter HTML dashboard
- `summary.txt`: the `analyze.py` summary
- `jmeter.log`

To re-analyze an existing run:

```bash
python3 loadtest/analyze.py loadtest/results/<run>/results.jtl \
    --initial-stock <N> --final-stock <N> --orders <N>
```

## Reading the summary

- **Response codes**
  - `200` means a confirmed purchase.
  - `409` means out of stock, or the optimistic-lock retries were exhausted.
  - `429` means the per-product lock wasn't acquired within 2 s.
  - Anything else is flagged `<- unexpected` and fails the run.
- **Latency**: min, mean and nearest-rank percentiles (p50 to p99) of JMeter's `elapsed` time, in milliseconds.
- **Throughput**: all requests per second, plus confirmed purchases (200s) per second, both over the wall-clock span from the first request start to the last request end.
- **Peak in-flight**: the most requests open at the same instant. It's computed with a sweep line over each request's `[start, start + elapsed]` interval. This shows how concurrent the run really was. A burst run should get close to `THREADS`. If it's far below, the load generator or server queued the requests instead of running them together.
- **Little's law average**: throughput × mean latency, which is the average number of requests in flight. Compare it with the peak: a low average with a high peak means short bursts of contention.
- **Invariants**
  - `finalStock >= 0`
  - `orders == initialStock - finalStock`
  - `count(200) == orders`
  - no unexpected codes

  Any failure means oversell, a lost order, or a server error, and the script exits non-zero.

## Methodology

- **Discard a warm-up run.** The first run after starting the app warms up the JIT, the connection pools and the Redis connections, and isn't representative.
- **Run each scenario 3 times** with the same settings, and report the median and the spread rather than a single best run.
- **Record the environment** (machine, CPU and memory given to Docker, JMeter running on the same host or not) next to any numbers you publish. When JMeter shares a laptop with the app, MySQL and Redis, both sides compete for the same CPU.
