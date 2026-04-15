package com.chatflow.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drains the publish buffer and batch-publishes to Redis using pipelines.
 * Multiple workers share the same buffer — each drains what it can.
 *
 * Decouples WebSocket worker threads from Redis I/O:
 * - onMessage() just enqueues (O(1), never blocks on Redis)
 * - These workers handle the actual PUBLISH + XADD in batches
 */
public class RedisPublisherWorker implements Runnable {

    private static final int BATCH_SIZE = 200;
    private static final int POLL_MS    = 5;

    private final BlockingQueue<ChatMessage> buffer;
    private final RedisPublisher publisher;
    private final String workerName;

    // pipeline checkpoint 2: published to Redis stream
    private final AtomicLong totalPublished = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();

    public long getTotalPublished() { return totalPublished.get(); }
    public long getTotalFailed()    { return totalFailed.get(); }

    public RedisPublisherWorker(BlockingQueue<ChatMessage> buffer, RedisPublisher publisher, String workerName) {
        this.buffer     = buffer;
        this.publisher  = publisher;
        this.workerName = workerName;
    }

    @Override
    public void run() {
        List<ChatMessage> batch = new ArrayList<>(BATCH_SIZE);
        System.out.println("[" + workerName + "] started");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                buffer.drainTo(batch, BATCH_SIZE);

                if (batch.isEmpty()) {
                    ChatMessage msg = buffer.poll(POLL_MS, TimeUnit.MILLISECONDS);
                    if (msg != null) batch.add(msg);
                }

                if (!batch.isEmpty()) {
                    try {
                        publisher.publishBatch(batch);
                        long n = totalPublished.addAndGet(batch.size());
                        System.out.printf("[%s] checkpoint-2: batch=%d totalPublished=%d totalFailed=%d%n",
                                workerName, batch.size(), n, totalFailed.get());
                    } catch (Exception e) {
                        totalFailed.addAndGet(batch.size());
                        System.out.printf("[%s] publish FAILED batch=%d totalFailed=%d: %s%n",
                                workerName, batch.size(), totalFailed.get(), e.getMessage());
                    }
                    batch.clear();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}