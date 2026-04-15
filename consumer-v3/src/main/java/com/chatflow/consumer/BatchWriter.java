package com.chatflow.consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Write-behind batch writer.
 *
 * Consumer threads drop ChatMessage objects into the buffer (non-blocking).
 * A dedicated writer thread drains the buffer and flushes to PostgreSQL in batches.
 *
 * This decouples the consume rate from the DB write rate — consumers are never
 * blocked waiting for a database round-trip.
 *
 * Includes a circuit breaker: after FAILURE_THRESHOLD consecutive failures the
 * circuit opens and flushes are skipped for a cooldown period. After cooldown a
 * single probe flush is attempted (HALF_OPEN). Success closes the circuit;
 * failure reopens it with doubled cooldown (exponential backoff).
 */
public class BatchWriter implements Runnable {

    // tunable batch parameters
    private final int batchSize;
    private final long flushIntervalMs;

    private final DatabaseConnectionPool dbPool;
    private final BlockingQueue<ChatMessage> buffer; // shared across all writer threads

    // metrics
    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong writeErrors  = new AtomicLong(0);

    // ── Circuit breaker ───────────────────────────────────────────────────────
    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private static final int  FAILURE_THRESHOLD   = 3;
    private static final long INITIAL_COOLDOWN_MS = 30_000;   // 30s
    private static final long MAX_COOLDOWN_MS     = 300_000;  // 5min cap

    private CircuitState circuitState       = CircuitState.CLOSED;
    private int          consecutiveFailures = 0;
    private long         circuitOpenedAt     = 0;
    private long         cooldownMs          = INITIAL_COOLDOWN_MS;

    private static final String INSERT_SQL =
            "INSERT INTO messages (message_id, room_id, user_id, username, message, message_type, server_id, client_ip, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (message_id, timestamp) DO NOTHING";  // idempotent — partition PK includes timestamp

    public BatchWriter(DatabaseConnectionPool dbPool, BlockingQueue<ChatMessage> buffer,
                       int batchSize, long flushIntervalMs) {
        this.dbPool = dbPool;
        this.buffer = buffer;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        System.out.println("BatchWriter initialized: batchSize=" + batchSize + " flushInterval=" + flushIntervalMs + "ms");
    }

    /**
     * Writer thread: drains buffer and flushes to DB in batches.
     * Flushes when batch is full OR flush interval expires — whichever comes first.
     */
    @Override
    public void run() {
        List<ChatMessage> batch = new ArrayList<>(batchSize);
        long lastFlush = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // drain up to batchSize messages from buffer
                buffer.drainTo(batch, batchSize);

                long now = System.currentTimeMillis();
                boolean intervalExpired = (now - lastFlush) >= flushIntervalMs;

                if (!batch.isEmpty() && (batch.size() >= batchSize || intervalExpired)) {
                    flushWithCircuitBreaker(batch);
                    lastFlush = System.currentTimeMillis();
                    batch.clear();
                } else {
                    // batch not ready yet — sleep to avoid busy-spinning
                    Thread.sleep(10);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                writeErrors.incrementAndGet();
                System.out.println("BatchWriter error: " + e.getMessage());
                batch.clear();
            }
        }

        // flush remaining messages on shutdown
        if (!batch.isEmpty()) {
            flushWithCircuitBreaker(batch);
        }
    }

    private void flushWithCircuitBreaker(List<ChatMessage> batch) {
        if (circuitState == CircuitState.OPEN) {
            if (System.currentTimeMillis() - circuitOpenedAt >= cooldownMs) {
                circuitState = CircuitState.HALF_OPEN;
                System.out.println("[CIRCUIT] HALF_OPEN — probing DB");
            } else {
                System.out.printf("[CIRCUIT] OPEN — dropping %d messages (pending in Redis Stream)%n", batch.size());
                return;
            }
        }

        boolean success = flush(batch);

        if (success) {
            if (circuitState == CircuitState.HALF_OPEN) {
                circuitState = CircuitState.CLOSED;
                consecutiveFailures = 0;
                cooldownMs = INITIAL_COOLDOWN_MS;
                System.out.println("[CIRCUIT] CLOSED — DB recovered");
            } else {
                consecutiveFailures = 0;
            }
        } else {
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                circuitState    = CircuitState.OPEN;
                circuitOpenedAt = System.currentTimeMillis();
                cooldownMs      = Math.min(cooldownMs * 2, MAX_COOLDOWN_MS);
                System.out.printf("[CIRCUIT] OPEN — %d consecutive failures, cooldown=%ds%n",
                        consecutiveFailures, cooldownMs / 1000);
            }
        }
    }

    private boolean flush(List<ChatMessage> batch) {
        long start = System.currentTimeMillis();
        try (Connection conn = dbPool.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            for (ChatMessage msg : batch) {
                stmt.setString(1, msg.getMessageId().toString());
                stmt.setString(2, msg.getRoomId());
                stmt.setString(3, msg.getUserId());
                stmt.setString(4, msg.getUsername());
                stmt.setString(5, msg.getMessage());
                stmt.setString(6, msg.getMessageType() != null ? msg.getMessageType().toString() : "TEXT");
                stmt.setString(7, msg.getServerId());
                stmt.setString(8, msg.getClientIp());
                stmt.setTimestamp(9, Timestamp.from(Instant.parse(msg.getTimestamp())));
                stmt.addBatch();
            }

            stmt.executeBatch();
            long elapsed = System.currentTimeMillis() - start;
            totalWritten.addAndGet(batch.size());
            totalBatches.incrementAndGet();
            System.out.printf("[DB] flushed %d messages in %dms | total=%d%n",
                    batch.size(), elapsed, totalWritten.get());
            return true;

        } catch (Exception e) {
            writeErrors.incrementAndGet();
            System.out.println("Batch flush failed: " + e.getMessage());
            return false;
        }
    }

    public long getTotalWritten()  { return totalWritten.get(); }
    public long getTotalBatches()  { return totalBatches.get(); }
    public long getWriteErrors()   { return writeErrors.get(); }
}
