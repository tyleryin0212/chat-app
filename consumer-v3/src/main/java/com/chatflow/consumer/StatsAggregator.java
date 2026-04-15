package com.chatflow.consumer;

import java.util.concurrent.BlockingQueue;

/**
 * Periodically aggregates write statistics across all BatchWriter threads
 * and logs a system-wide summary.
 *
 * Runs on a ScheduledExecutorService (the stats aggregator thread pool).
 * Complements the per-room metrics logged by each RoomConsumer.
 */
public class StatsAggregator implements Runnable {

    private static final int INTERVAL_SEC = 10;

    private final BatchWriter[] writers;
    private final BlockingQueue<ChatMessage> writeBuffer;

    // snapshots for delta calculation
    private long lastTotalWritten = 0;
    private long lastCheckMs      = System.currentTimeMillis();

    public StatsAggregator(BatchWriter[] writers, BlockingQueue<ChatMessage> writeBuffer) {
        this.writers     = writers;
        this.writeBuffer = writeBuffer;
    }

    @Override
    public void run() {
        long totalWritten = 0, totalBatches = 0, totalErrors = 0;
        for (BatchWriter w : writers) {
            totalWritten += w.getTotalWritten();
            totalBatches += w.getTotalBatches();
            totalErrors  += w.getWriteErrors();
        }

        long now     = System.currentTimeMillis();
        long elapsed = now - lastCheckMs;
        long delta   = totalWritten - lastTotalWritten;
        long writeRate = elapsed > 0 ? delta * 1000 / elapsed : 0;

        System.out.printf("[STATS] written=%d | batches=%d | errors=%d | writeRate=%d/sec | bufferDepth=%d%n",
                totalWritten, totalBatches, totalErrors, writeRate, writeBuffer.size());

        lastTotalWritten = totalWritten;
        lastCheckMs      = now;
    }

    public static int intervalSec() { return INTERVAL_SEC; }
}