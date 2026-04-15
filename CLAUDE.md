# CS6650 Assignment 3 & 4 — Project Context

## What this project is

A distributed chat system built for Northeastern CS6650 (Building Scalable Distributed Systems).
- **Assignment 3**: Added persistence (high-throughput DB writes) on top of a WebSocket chat server + RabbitMQ consumer pipeline.
- **Assignment 4 (current)**: Group optimization assignment — implement 2 material performance improvements and measure with JMeter.

---

## Current architecture (Assignment 3 baseline)

```
client-v3/        → Load test client (Java, sends WebSocket messages)
server-v3/        → WebSocket chat server + Metrics API (HTTP)
consumer-v3/      → RabbitMQ consumer → batched DB writes
database/         → PostgreSQL schema
load-tests/       → Test results and reports
```

### Message flow (current)
```
LoadTestClient → WebSocket (8080) → ChatServer → RabbitMQ → RoomConsumer → writeBuffer → BatchWriter → PostgreSQL
                                                                  ↓
                                                        BroadcastClient (HTTP POST → AdminServer 8081 → sessionManager → WebSocket clients)
```

### Key classes

**server-v3:**
- `ChatServer` — WebSocket listener, validates + publishes to RabbitMQ
- `RabbitPublisher` — channel pool + circuit breaker, publishes to RabbitMQ topic exchange
- `MessageValidator` — validates all fields (UUID, room 1-20, user 1-100000, etc.)
- `AdminServer` — HTTP on 8081: `/health`, `/broadcast/{roomId}`, `/metrics`
- `MetricsService` — JDBC queries to PostgreSQL for analytics JSON (called after load test)
- `RoomSessionManager` — thread-safe `room → Set<WebSocket>` for local fan-out

**consumer-v3:**
- `RoomConsumer` — 1 thread per room, dedup (LRU-1000), acks after enqueue
- `BatchWriter` — 5 threads, drains writeBuffer, batches to PostgreSQL, has circuit breaker
- `DeadLetterQueue` — in-memory retry queue for failed DB batches (retries every 30s)
- `BroadcastClient` — async HTTP POST to server `/broadcast/{roomId}` for fan-out
- `DatabaseConnectionPool` — HikariCP wrapper (max 5 connections)
- `StatsAggregator` — logs throughput/buffer stats every 10s

**client-v3:**
- `LoadTestClient` — orchestrates test, polls `/metrics` until DB count stabilizes
- `MessageGenerator` — produces batches: 1 JOIN + 18 TEXT + 1 LEAVE per user session
- `SenderThread` — N threads, WebSocket connection pool per room, retry w/ exponential backoff
- `MetricsCollector` — atomic counters → throughput summary

### Database schema (current)
Single table `messages`, PK is `message_id` (VARCHAR UUID). Three composite B-tree indexes:
- `(room_id, timestamp)` — room range queries
- `(user_id, timestamp)` — user history
- `(timestamp)` — active user counts

---

## Assignment 4 planned changes (NOT YET IMPLEMENTED)

These are the two material optimizations decided on. Implementation starts in a new folder copied from this one.

### Optimization 1: Replace RabbitMQ with Redis (Pub/Sub + Streams)

**Problem being solved:** Single server bottleneck. Currently `BroadcastClient` HTTP-posts to one hardcoded server URL — with multiple servers, clients on other servers never receive broadcasts. Also consolidates infrastructure from RabbitMQ + future Redis → Redis only.

**New message flow:**
```
ChatServer.onMessage()
  → RedisPublisher.publish()
       ├─ PUBLISH room:{roomId}     → RedisSubscriber (on ALL servers) → sessionManager.broadcast() → local WebSocket clients
       └─ XADD room-stream          → RedisStreamConsumer (consumer group "db-writers") → writeBuffer → BatchWriter → PostgreSQL
```

**New classes to create:**

`server-v3`:
- `RedisPublisher` — called from `ChatServer.onMessage()`. Does both: `PUBLISH room:{roomId}` (Pub/Sub fan-out) + `XADD room-stream` (Stream for DB persistence). Replaces `RabbitPublisher`.
- `RedisSubscriber` — subscribes to `room:1` through `room:20` on startup. On message received → `sessionManager.broadcast()`. Runs on its own thread (Pub/Sub blocks connection). Replaces the HTTP `/broadcast` callback pattern.

`consumer-v3`:
- `RedisStreamConsumer` — replaces `RoomConsumer`. Uses `XREADGROUP` with consumer group `"db-writers"` to read from Redis Stream. Feeds messages into `writeBuffer`. Does NOT `XACK` until message is safely in writeBuffer. On DB failure, message stays pending in stream — stream replaces DeadLetterQueue.

**Classes to delete:**
- `RabbitPublisher` (server-v3)
- `RoomConsumer` (consumer-v3)
- `BroadcastClient` (consumer-v3) — Redis Pub/Sub handles fan-out now, consumer no longer broadcasts
- `DeadLetterQueue` (consumer-v3) — Redis Stream pending entries replace it

**Changes to existing classes:**
- `ChatServer.onMessage()` — call `RedisPublisher` instead of `RabbitPublisher`
- `AdminServer` — remove `/broadcast` endpoint (no longer needed)
- `server-v3/Main` — initialize `RedisPublisher` + `RedisSubscriber`, remove RabbitMQ setup
- `consumer-v3/Main` — start `RedisStreamConsumer` threads, remove RabbitMQ + `BroadcastClient` + `DeadLetterQueue`
- Both `pom.xml` files — add Jedis dependency, remove RabbitMQ dependency

**Redis persistence:** Enable AOF in `redis.conf` with `appendonly yes` — one line, ensures messages survive Redis restart.

**Why not Kafka:** Also persistent and partitioned, but adds a third infrastructure component (already adding Redis). No measurable DB write throughput improvement since PostgreSQL is the bottleneck, not the queue. Redis Streams at same infrastructure cost handles 500k-1M messages comfortably.

**Why not keep RabbitMQ:** Works fine but requires running two separate messaging systems (RabbitMQ + Redis). Consolidating to Redis alone is cleaner and a defensible optimization.

---

### Optimization 2: PostgreSQL time partitioning

**Problem being solved:** Write throughput visibly degrades as table grows. Root cause: 3 B-tree indexes all grow deeper with each insert — every INSERT updates all 3 indexes, each taking longer as the tree deepens.

**Fix:** Partition the `messages` table by `timestamp` range. Each partition has its own small indexes. New inserts always go to the latest (small) partition. Old partitions are never touched by new inserts. All existing queries in `MetricsService` work unchanged — PostgreSQL routes automatically.

**Schema changes required:**
- PK changes from `(message_id)` to `(message_id, timestamp)` — PostgreSQL requires partition key in PK
- Add `PARTITION BY RANGE (timestamp)` to table definition
- Create partitions for current time ranges (2026 Q2, Q3 at minimum)
- Indexes defined on parent table, automatically propagated to each partition

**No application code changes needed** — `BatchWriter` SQL stays identical, `MetricsService` queries unchanged.

**Why not Cassandra:** Cassandra uses "query-driven modeling" — one table per query pattern. Every INSERT would need to write to 4-5 denormalized tables. Analytics queries (top users, top rooms, COUNT DISTINCT) are not natively supported and require application-level aggregation or pre-maintained counter tables. Too much rewrite for this assignment.

**Why not TimescaleDB:** Also a strong option (PostgreSQL extension, automatic hypertable partitioning). Manual partitioning chosen for simplicity and transparency — same result, no extension dependency.

---

## Implementation todo list

| # | Task | Status |
|---|---|---|
| 1 | Add Jedis dependency, remove RabbitMQ from both pom.xml files | pending |
| 2 | Create `RedisPublisher` in server-v3 | pending |
| 3 | Create `RedisSubscriber` in server-v3 | pending |
| 4 | Update `ChatServer`, `AdminServer`, `Main` in server-v3 + delete `RabbitPublisher` | pending |
| 5 | Create `RedisStreamConsumer` in consumer-v3 | pending |
| 6 | Update `Main` in consumer-v3 + delete `RoomConsumer`, `BroadcastClient`, `DeadLetterQueue` | pending |
| 7 | Update `schema.sql` for time partitioning + enable Redis AOF | pending |
| 8 | EC2 deployment setup — SSH config + env file (once EC2 instance count is decided) | pending |

### Task 8 notes — EC2 deployment setup
EC2 IPs change every AWS Academy Lab session. Once the number of instances is decided, set up:
- `~/.ssh/config` on local machine with Host aliases per instance (only IPs need updating each session, not scripts)
- `ec2-hosts.sh` in project root with `export SERVER1_IP=...` etc. — `source ec2-hosts.sh` at session start
- All SSH/SCP deployment commands use aliases + env vars so nothing else changes when IPs rotate

Likely instances needed (decide count first):
- 1 or 2 EC2s for server-v3 (multiple instances on same EC2 on different ports is fine, fronted by AWS ALB)
- 1 EC2 for Redis + consumer-v3 (co-locate, they communicate constantly)
- 1 EC2 for PostgreSQL (already running at 54.188.60.194 — confirm if this persists across lab sessions)
- AWS ALB in front of server EC2(s) with target groups pointing to each server port

---

## Assignment 4 report requirements

- PDF, max 8 pages
- Architecture selection rationale (5 pts)
- Optimization 1 write-up: what/why/tradeoffs + perf data (7.5 pts)
- Optimization 2 write-up: what/why/tradeoffs + perf data (7.5 pts)
- 3-5 future optimization ideas with impact/complexity assessment (5 pts)
- JMeter results: baseline vs optimized, p95/p99 latency, throughput, error rate (10 pts)

### JMeter test scenarios
- **Baseline**: 1000 concurrent users, 100K API calls, 5 min, 70% read / 30% write
- **Stress**: 500 concurrent users, 200K-500K calls, 30 min, mixed read/write

---

## Key design decisions (for reference / report)

- **Denormalized single table** — intentional. FK validation at 1M inserts/test would add 2M extra lookups + lock contention. username never changes so duplication is acceptable for an append-only event log.
- **Write-behind pattern** — `writeBuffer` (BlockingQueue, 100k cap) decouples RabbitMQ/Redis consumption rate from DB write rate. Server never waits on DB.
- **Circuit breaker in BatchWriter** — 3 failures → open, exponential backoff (30s → 5min cap). With Redis Streams, failed messages stay pending instead of going to in-memory DLQ.
- **Idempotent writes** — `ON CONFLICT (message_id) DO NOTHING` + client-side LRU dedup (1000 entries).
- **Batch tuning** — default batchSize=500, flushIntervalMs=500. Configurable via CLI args.