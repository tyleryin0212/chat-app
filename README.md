# ChatFlow — Distributed Chat System

A high-throughput distributed chat system built for 1M concurrent requests. Supports horizontal server scaling, real-time WebSocket fan-out via Redis Pub/Sub, durable message persistence via Redis Streams, and a PostgreSQL analytics API.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Load Balancer                            │
└──────────────────────┬──────────────────────────────────────────┘
                       │ WebSocket (ws://)
          ┌────────────┼────────────┐
          ▼            ▼            ▼
     [Server A]   [Server B]   [Server C]      ← any number of instances
          │            │            │
          └────────────┼────────────┘
                       │
               ┌───────┴────────┐
               ▼                ▼
     Redis PUBLISH          Redis XADD
     room:{roomId}          room-stream
     (Pub/Sub fan-out)      (persistence queue)
               │                │
    ┌──────────┴──────┐         │
    ▼    ▼    ▼       ▼         ▼
 Srv-A Srv-B Srv-C  ...   [Consumer]
 (each fans out to           │
  local WS clients)    writeBuffer
                             │
                       [BatchWriter x5]
                             │
                       PostgreSQL (time-partitioned)
```

### Key design properties

- **Horizontally scalable servers** — clients connect to any server instance; Redis Pub/Sub ensures all servers receive and fan-out every message to their local WebSocket clients.
- **Write-behind persistence** — messages are enqueued to a Redis Stream for async DB writes, decoupling consume rate from DB write rate.
- **Durable message queue** — Redis Stream with AOF persistence; unacknowledged messages stay pending and survive consumer restarts.
- **Time-partitioned PostgreSQL** — `messages` table partitioned by `timestamp` range; each partition maintains its own small indexes, keeping write throughput stable as data grows.
- **Idempotent writes** — `ON CONFLICT (message_id) DO NOTHING` prevents duplicates from retries.
- **Circuit breaker in BatchWriter** — 3 consecutive DB failures opens the circuit, exponential backoff (30s → 5min cap), half-open probe to recover.

---

## Project structure

```
chatflow/
├── server-v3/       # WebSocket chat server + HTTP Metrics API
├── consumer-v3/     # Redis Stream consumer → batched PostgreSQL writes
├── client-v3/       # Multi-threaded load test client
└── database/        # PostgreSQL schema (time-partitioned)
```

---

## Prerequisites

| Dependency | Version | Purpose |
|---|---|---|
| Java | 11+ | All modules |
| Maven | 3.6+ | Build |
| Redis | 6+ | Pub/Sub fan-out + Stream queue |
| PostgreSQL | 13+ | Persistent message storage |

---

## Setup

### 1. Redis

```bash
# Install and start Redis
brew install redis        # macOS
redis-server

# Enable AOF persistence (add to redis.conf or pass inline)
redis-server --appendonly yes
```

### 2. PostgreSQL

```bash
# Create database and user
psql -U postgres -c "CREATE USER chatflow WITH PASSWORD '123456';"
psql -U postgres -c "CREATE DATABASE chatdb OWNER chatflow;"

# Apply schema (time-partitioned)
psql -U chatflow -d chatdb -f database/schema.sql
```

### 3. Build all modules

```bash
cd server-v3   && mvn clean package -q && cd ..
cd consumer-v3 && mvn clean package -q && cd ..
cd client-v3   && mvn clean package -q && cd ..
```

---

## Running

Start components in this order: **Redis → PostgreSQL → Server(s) → Consumer → Client**

### Server

```bash
java -jar server-v3/target/server-v3-1.0-SNAPSHOT.jar \
  [port] [serverId] [redisHost] [dbHost]
```

| Argument | Default | Description |
|---|---|---|
| `port` | `8080` | WebSocket listen port |
| `serverId` | `server-1` | Unique ID for this server instance |
| `redisHost` | `localhost` | Redis host |
| `dbHost` | `localhost` | PostgreSQL host |

Example (two instances):
```bash
java -jar server-v3/target/server-v3-1.0-SNAPSHOT.jar 8080 server-1 localhost db-host
java -jar server-v3/target/server-v3-1.0-SNAPSHOT.jar 8081 server-2 localhost db-host
```

### Consumer

```bash
java -jar consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar \
  [serverId] [redisHost] [dbHost] [batchSize] [flushIntervalMs]
```

| Argument | Default | Description |
|---|---|---|
| `serverId` | `server-1` | Must match a running server's serverId |
| `redisHost` | `localhost` | Redis host |
| `dbHost` | `localhost` | PostgreSQL host |
| `batchSize` | `500` | Max messages per DB batch insert |
| `flushIntervalMs` | `500` | Max ms before flushing a partial batch |

Example:
```bash
java -jar consumer-v3/target/consumer-v3-1.0-SNAPSHOT.jar \
  server-1 localhost db-host 500 500
```

### Load test client

```bash
java -jar client-v3/target/client-v3-1.0-SNAPSHOT.jar \
  [totalMessages] [serverWsUrl] [metricsUrl] [threads]
```

| Argument | Default | Description |
|---|---|---|
| `totalMessages` | `500000` | Total messages to send |
| `serverWsUrl` | `ws://localhost:8080/chat/` | WebSocket base URL (trailing slash required) |
| `metricsUrl` | `http://localhost:8081/metrics` | Metrics API URL to poll post-test |
| `threads` | `20` | Concurrent sender threads |

Example:
```bash
# Baseline test (500k messages)
java -jar client-v3/target/client-v3-1.0-SNAPSHOT.jar \
  500000 ws://server-host:8080/chat/ http://server-host:8081/metrics 20

# Stress test (1M messages)
java -jar client-v3/target/client-v3-1.0-SNAPSHOT.jar \
  1000000 ws://server-host:8080/chat/ http://server-host:8081/metrics 40
```

---

## Metrics API

The server exposes a read-only HTTP analytics endpoint populated from PostgreSQL.

```
GET http://<server-host>:8081/metrics
```

**Response fields:**

```json
{
  "queriedAt": "2026-04-08T12:00:00Z",
  "totalMessages": 500000,
  "messagesByType": { "TEXT": 450000, "JOIN": 25000, "LEAVE": 25000 },
  "activeUsersLastHour": 8500,
  "topUsers": [
    { "userId": "42301", "username": "user42301", "messageCount": 312 }
  ],
  "topRooms": [
    { "roomId": "7", "messageCount": 28000 }
  ],
  "messagesPerMinute": [
    { "minute": "2026-04-08T11:45:00Z", "count": 9800 }
  ],
  "room1RecentMessages": [ { "messageId": "...", "message": "...", "timestamp": "..." } ]
}
```

**Supported queries:**
1. Messages for a room in a time range — target < 100ms
2. User message history — target < 200ms
3. Active user count in time window — target < 500ms
4. Rooms a user participated in (with last activity) — target < 50ms

---

## Message format (WebSocket)

Clients send JSON to `ws://<host>:<port>/chat/{roomId}`:

```json
{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "42301",
  "username": "user42301",
  "message": "Hello everyone!",
  "roomId": "7",
  "timestamp": "2026-04-08T12:00:00.000Z",
  "messageType": "TEXT",
  "serverId": "server-1",
  "clientIp": "10.0.0.1"
}
```

**Validation rules:**
- `messageId` — valid UUID
- `roomId` — integer 1–20
- `userId` — integer 1–100000
- `username` — alphanumeric, 3–20 characters
- `message` — non-empty, max 500 characters
- `messageType` — `TEXT`, `JOIN`, or `LEAVE`
- `timestamp` — valid ISO-8601 instant

---

## Database schema

```sql
CREATE TABLE messages (
    message_id   VARCHAR(36)  NOT NULL,
    room_id      VARCHAR(10)  NOT NULL,
    user_id      VARCHAR(10)  NOT NULL,
    username     VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    message_type VARCHAR(10)  NOT NULL,
    server_id    VARCHAR(50),
    client_ip    VARCHAR(45),
    timestamp    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (message_id, timestamp)
) PARTITION BY RANGE (timestamp);

-- Quarterly partitions (each maintains its own small indexes)
CREATE TABLE messages_2026_q2 PARTITION OF messages
    FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');

CREATE TABLE messages_2026_q3 PARTITION OF messages
    FOR VALUES FROM ('2026-07-01') TO ('2026-10-01');
```

Indexes are defined on the parent table and automatically propagated to each partition:
- `(room_id, timestamp)` — room range queries
- `(user_id, timestamp)` — user history + room participation
- `(timestamp)` — active user count in time window

---

## Key components

### server-v3

| Class | Role |
|---|---|
| `ChatServer` | WebSocket server (port 8080). Validates messages, calls `RedisPublisher`. |
| `RedisPublisher` | On each message: `PUBLISH room:{roomId}` (Pub/Sub fan-out) + `XADD room-stream` (Stream for DB persistence). |
| `RedisSubscriber` | Subscribes to `room:1`–`room:20` at startup. On message received → `sessionManager.broadcast()` to local WebSocket clients. Runs on its own thread. |
| `RoomSessionManager` | Thread-safe `room → Set<WebSocket>` registry for local fan-out. |
| `MessageValidator` | Validates all message fields before publish. |
| `AdminServer` | HTTP on 8081: `GET /health`, `GET /metrics`. |
| `MetricsService` | Executes analytics queries against PostgreSQL, returns JSON. |

### consumer-v3

| Class | Role |
|---|---|
| `RedisStreamConsumer` | Reads from Redis Stream using `XREADGROUP` (consumer group `"db-writers"`). Feeds messages into `writeBuffer`. `XACK` only after enqueue — unacknowledged messages stay pending in stream for durability. |
| `BatchWriter` | 5 threads drain `writeBuffer`, batch-insert to PostgreSQL. Configurable `batchSize` and `flushIntervalMs`. Has circuit breaker (3 failures → open, exponential backoff). |
| `DatabaseConnectionPool` | HikariCP wrapper. One connection per `BatchWriter` thread. |
| `StatsAggregator` | Logs throughput, batch count, error rate, buffer depth every 10s. |

### client-v3

| Class | Role |
|---|---|
| `LoadTestClient` | Orchestrates test. Starts generator + senders, waits for completion, polls `/metrics` until DB count stabilizes, prints full metrics JSON. |
| `MessageGenerator` | Produces batches of 20 messages per user session (1 JOIN + 18 TEXT + 1 LEAVE). Random userId (1–100000), roomId (1–20). |
| `SenderThread` | Sends batches via WebSocket. Maintains per-room connection pool. Retry with exponential backoff (up to 5 attempts, 100ms × 2^attempt). |
| `MetricsCollector` | Thread-safe atomic counters. Reports success/fail counts, throughput, connections, reconnections. |

---

## Tuning parameters

### BatchWriter
- `batchSize` — messages per batch insert (tested: 100, 500, 1000, 5000). Default: 500.
- `flushIntervalMs` — max wait before flushing a partial batch (tested: 100ms, 500ms, 1000ms). Default: 500ms.
- Larger batch = fewer DB round-trips but higher latency. Tune for your DB throughput.

### RedisStreamConsumer
- Consumer group: `"db-writers"`
- Block timeout on `XREADGROUP`: 100ms (allows clean shutdown)
- Pending message reclaim: on startup, claims any messages left pending from a crashed prior instance

### HikariCP (DatabaseConnectionPool)
- Max pool size: 5 (matches `NUM_WRITERS`)
- Connection timeout: 30s
- Idle timeout: 10min
- Max lifetime: 30min
- Prepared statement cache: 25 entries

---

## Load test scenarios

### Baseline
- 500,000 messages, 20 sender threads
- Metrics: write throughput, p50/p95/p99 latency, DB CPU/memory, queue depth stability

### Stress test
- 1,000,000 messages, 40 sender threads
- Goals: find bottlenecks, document degradation curve

### Endurance test
- Sustained rate for 30+ minutes at 80% of max throughput
- Monitor: memory leaks, connection pool exhaustion, performance degradation over time