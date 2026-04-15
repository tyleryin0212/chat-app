# CS6650 Assignment 4 — Project Context

## What this project is

A distributed chat system built for Northeastern CS6650 (Building Scalable Distributed Systems).
- **Assignment 3**: Added persistence (high-throughput DB writes) on top of a WebSocket chat server + RabbitMQ consumer pipeline.
- **Assignment 4 (implemented)**: Two material performance optimizations — (1) replaced RabbitMQ with Redis Pub/Sub + Streams, (2) PostgreSQL time-partitioned table.

---

## Current architecture (Assignment 4 — fully implemented)

```
client-v3/        → Load test client (Java, sends WebSocket messages)
server-v3/        → WebSocket chat server + HTTP Metrics/Stats API
consumer-v3/      → Redis Stream consumer → batched PostgreSQL writes
database/         → PostgreSQL schema (time-partitioned)
```

### Message flow
```
LoadTestClient
  → WebSocket (port 8080)
  → ChatServer.onMessage()
  → publishBuffer (LinkedBlockingQueue, 500k cap)
  → RedisPublisherWorker (4 threads, batch=200)
  → RedisPublisher.publishBatch()
       ├─ PUBLISH room:{roomId}            → RedisSubscriber (on ALL servers)
       │                                      → sessionManager.broadcast()
       │                                      → local WebSocket clients
       └─ XADD room-stream:{roomId}        → RedisStreamConsumer (1 thread/room)
                                              → writeBuffer (BlockingQueue, 100k cap)
                                              → BatchWriter (5 threads)
                                              → PostgreSQL (time-partitioned)
```

### Pipeline checkpoints (logged at runtime)
```
checkpoint-1: ChatServer      — received, enqueued, failedValidation, bufferFull
checkpoint-2: PublisherWorker — totalPublished, totalFailed (per worker)
checkpoint-3: StreamConsumer  — processed, duplicates, errors (per room, every 10s)
              BatchWriter     — totalWritten, totalBatches, dbErrors
```
The load test client fetches all four checkpoints at end of test and prints a full pipeline summary.

---

## Key classes

**server-v3:**
- `ChatServer` — WebSocket server (64 threads). Validates message, enqueues to `publishBuffer`. Never calls Redis directly.
- `RedisPublisher` — `publishBatch()` uses a Jedis pipeline: one `PUBLISH room:{roomId}` + one `XADD room-stream:{roomId}` per message. JedisPool (max 100). Circuit breaker: 5 failures → open, 10s cooldown.
- `RedisPublisherWorker` — drains `publishBuffer` and calls `RedisPublisher.publishBatch()`. 4 workers share the buffer, each draining up to 200 messages per pass (poll timeout 5ms). Decouples WebSocket I/O from Redis I/O.
- `RedisSubscriber` — subscribes to channels `room:1`–`room:20` on startup. On message received → `sessionManager.broadcast()`. Dedicated blocking connection (Pub/Sub blocks); reconnects on disconnect (2s delay). Runs on its own daemon thread.
- `RoomSessionManager` — thread-safe `ConcurrentHashMap<roomId, Set<WebSocket>>` for local fan-out. Note: lives in `com.chatflow.consumer` package but is part of server-v3.
- `MessageValidator` — validates all fields (UUID, roomId 1-20, userId 1-100000, username alphanumeric 3-20, message non-empty ≤500 chars, messageType TEXT/JOIN/LEAVE, timestamp ISO-8601).
- `AdminServer` — HTTP on `port+1` (e.g., 8081). Endpoints: `GET /health`, `GET /metrics`, `GET /pipeline-stats`.
- `MetricsService` — JDBC queries to PostgreSQL for analytics JSON.

**consumer-v3:**
- `RedisStreamConsumer` — 1 thread per room, reads from `room-stream:{roomId}` using `XREADGROUP GROUP db-writers <consumerName> COUNT 100 BLOCK 1000`. Dedup LRU-1000. `XACK` only after successful `writeBuffer.offer()` — unacked entries stay pending for durability. On restart, prior pending entries are reclaimed automatically (group reads from last ack position).
- `BatchWriter` — 5 threads drain `writeBuffer`, batch-insert to PostgreSQL. Circuit breaker: 3 failures → open, exponential backoff (30s → 5min cap). `ON CONFLICT (message_id) DO NOTHING`.
- `DatabaseConnectionPool` — HikariCP (max 5, one per writer thread). Connection timeout 30s, idle timeout 10min, max lifetime 30min.
- `StatsAggregator` — logs throughput, batch count, error rate, buffer depth every 10s.
- `Main` — also starts a consumer stats HTTP server on port 9090: `GET /stats` returns `msgsConsumedFromStream`, `duplicatesSkipped`, `consumeErrors`, `msgsWrittenToDB`, `dbBatches`, `dbWriteErrors`, `writeBufferDepth`.

**client-v3:**
- `LoadTestClient` — orchestrates test. Args: `totalMessages serverWsUrl metricsUrl threads pipelineStatsUrl consumerStatsUrl`. After senders complete, polls `/metrics` until DB count stabilizes (2 consecutive equal readings 5s apart), then fetches `/pipeline-stats` (server, port+1) and `/stats` (consumer, 9090) and prints full pipeline summary.
- `MessageGenerator` — produces batches: 1 JOIN + 18 TEXT + 1 LEAVE per user session. Random userId (1–100000), roomId (1–20).
- `SenderThread` — sends batches via WebSocket. Per-room connection pool (one connection per room per thread). Retry with exponential backoff (up to 5 attempts, 100ms × 2^attempt).
- `MetricsCollector` — atomic counters for success/fail/received/connections/reconnections + throughput summary.

### Database schema
`messages` table partitioned `BY RANGE (timestamp)`. PK is `(message_id, timestamp)` (partition key must be in PK). Quarterly partitions: 2026-Q1 through 2026-Q4, 2027-Q1. Indexes on parent propagate automatically to each partition:
- `(room_id, timestamp)` — room range queries
- `(user_id, timestamp)` — user history
- `(timestamp)` — active user count window

---

## CLI args reference

### server-v3
```
java -jar server-v3/target/server-v3-1.0-SNAPSHOT.jar [port] [serverId] [redisHost] [dbHost]
```
Defaults: `8080`, `server-1`, `localhost`, `54.188.60.194`
Admin/metrics port is always `port+1` (e.g., 8081 when port=8080).

### consumer-v3
```
java -jar consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar [redisHost] [dbHost] [batchSize] [flushIntervalMs]
```
Defaults: `localhost`, `54.188.60.194`, `500`, `500`
Consumer stats always on port 9090.

### client-v3
```
java -jar client-v3/target/client-v3-1.0-SNAPSHOT.jar [totalMessages] [serverWsUrl] [metricsUrl] [threads] [pipelineStatsUrl] [consumerStatsUrl]
```
Defaults: `500000`, `ws://localhost:8080/chat/`, `http://localhost:8081/metrics`, `20`, `http://localhost:8081/pipeline-stats`, `http://localhost:9090/stats`

---

## Implementation todo list

| # | Task | Status |
|---|---|---|
| 1 | Add Jedis dependency, remove RabbitMQ from both pom.xml files | ✅ done |
| 2 | Create `RedisPublisher` in server-v3 | ✅ done |
| 3 | Create `RedisSubscriber` in server-v3 | ✅ done |
| 4 | Update `ChatServer`, `AdminServer`, `Main` in server-v3 + delete `RabbitPublisher` | ✅ done |
| 5 | Create `RedisStreamConsumer` in consumer-v3 | ✅ done |
| 6 | Update `Main` in consumer-v3 + delete `RoomConsumer`, `BroadcastClient`, `DeadLetterQueue` | ✅ done |
| 7 | Update `schema.sql` for time partitioning (Q1–Q4 2026, Q1 2027) | ✅ done |
| 7b | Enable Redis AOF (`appendonly yes` in redis.conf) | pending — runtime config |
| 8 | EC2 deployment setup — SSH config + env file | pending |

### Task 8 notes — EC2 deployment setup
EC2 IPs change every AWS Academy Lab session. Once the number of instances is decided, set up:
- `~/.ssh/config` on local machine with Host aliases per instance (only IPs need updating each session, not scripts)
- `ec2-hosts.sh` in project root with `export SERVER1_IP=...` etc. — `source ec2-hosts.sh` at session start
- All SSH/SCP deployment commands use aliases + env vars so nothing else changes when IPs rotate

Likely instances needed:
- 1 or 2 EC2s for server-v3 (multiple instances on same EC2 on different ports is fine, fronted by AWS ALB)
- 1 EC2 for Redis + consumer-v3 (co-locate, they communicate constantly)
- 1 EC2 for PostgreSQL (default in code: `54.188.60.194` — confirm if this persists across lab sessions)
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
- **publishBuffer between ChatServer and RedisPublisher** — `ChatServer.onMessage()` enqueues (O(1), never blocks on Redis). `RedisPublisherWorker` threads drain and batch-publish with Redis pipelines. Decouples WebSocket I/O from Redis I/O.
- **Write-behind pattern** — `writeBuffer` (BlockingQueue, 100k cap) decouples Redis Stream consumption from DB write rate. Consumer never waits on DB.
- **Redis Streams as DLQ replacement** — unacked entries stay pending in stream. On consumer restart, `XREADGROUP` automatically resumes from last ack position, reclaiming all pending messages. No separate in-memory DLQ needed.
- **Circuit breaker in RedisPublisher** — 5 failures → open, 10s cooldown. Circuit breaker in BatchWriter — 3 failures → open, exponential backoff (30s → 5min cap).
- **Idempotent writes** — `ON CONFLICT (message_id) DO NOTHING` + consumer-side LRU dedup (1000 entries per room).
- **Time partitioning** — each quarterly partition maintains its own small B-tree indexes. New inserts always land in the current (small) partition, keeping index depth constant regardless of total table size.
- **Why Redis over Kafka** — Kafka would be a third infrastructure component. Redis Streams handle 500k-1M messages comfortably at the same infrastructure cost as Redis Pub/Sub already needed.
- **Why manual partitioning over TimescaleDB** — same result, no extension dependency.
- **Batch tuning** — default batchSize=500, flushIntervalMs=500. Configurable via CLI args.