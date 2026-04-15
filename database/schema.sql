-- ChatFlow Assignment 4 — PostgreSQL Schema (time-partitioned)
-- Run as: psql -U chatflow -d chatdb -f schema.sql
--
-- Optimization: PARTITION BY RANGE (timestamp)
--   Each partition has its own small B-tree indexes.
--   New inserts always land in the latest (small) partition — index depth
--   stays constant regardless of total table size.
--   All existing queries in MetricsService work unchanged (PostgreSQL routes automatically).
--
-- PK change: (message_id) → (message_id, timestamp)
--   PostgreSQL requires the partition key to be part of the primary key.

-- Drop and recreate to apply partitioning (non-destructive ALTER is not possible)
DROP TABLE IF EXISTS messages CASCADE;

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
    PRIMARY KEY (message_id, timestamp)   -- partition key must be included in PK
) PARTITION BY RANGE (timestamp);

-- ── Partitions (quarterly) ────────────────────────────────────────────────────
-- Add new partitions before each quarter starts; old partitions are never touched
-- by new inserts so their indexes stay small and fast.

CREATE TABLE messages_2026_q1 PARTITION OF messages
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');

CREATE TABLE messages_2026_q2 PARTITION OF messages
    FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

CREATE TABLE messages_2026_q3 PARTITION OF messages
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');

CREATE TABLE messages_2026_q4 PARTITION OF messages
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

CREATE TABLE messages_2027_q1 PARTITION OF messages
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-04-01 00:00:00+00');

-- ── Indexes (defined on parent, propagated automatically to each partition) ────

-- Query 1: messages for a room in time range
CREATE INDEX idx_messages_room_time
    ON messages (room_id, timestamp);

-- Query 2: user message history
CREATE INDEX idx_messages_user_time
    ON messages (user_id, timestamp);

-- Query 3: count active users in time window
CREATE INDEX idx_messages_timestamp
    ON messages (timestamp);