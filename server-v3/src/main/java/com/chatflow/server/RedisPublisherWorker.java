package com.chatflow.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
    private static final int POLL_MS    = 1;

    private final BlockingQueue<ChatMessage> buffer;
    private final RedisPublisher publisher;
    private final String workerName;

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
                    } catch (Exception e) {
                        System.out.printf("[%s] publish FAILED batch=%d: %s%n",
                                workerName, batch.size(), e.getMessage());
                    }
                    batch.clear();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}