package com.chatflow.client;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class LoadTestClient {

    // ---- Configuration ----
    private static final String SERVER_BASE_URL  = "ws://localhost:8080/chat/";
    private static final int    TOTAL_MESSAGES   = 500000;
    private static final int    QUEUE_CAPACITY   = 10_000;
    private static final int    WARMUP_THREADS   = 32;
    private static final int    WARMUP_MESSAGES  = 1_000;   // per thread
    private static final int    MAIN_THREADS     = 100;

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        MetricsCollector metrics = new MetricsCollector();

        int warmupTotal = WARMUP_THREADS * WARMUP_MESSAGES;   // 32,000
        int mainTotal   = TOTAL_MESSAGES - warmupTotal;       // 468,000

        // Start message generator — produces all 500K messages into the queue
        Thread generatorThread = new Thread(
                new MessageGenerator(queue, TOTAL_MESSAGES), "msg-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();

        metrics.startTimer();

        // Warmup phase: 32 threads × 1000 messages each
        long warmupStart = System.currentTimeMillis();
        CountDownLatch warmupLatch = new CountDownLatch(WARMUP_THREADS);

        for (int i = 0; i < WARMUP_THREADS; i++) {
            Thread t = new Thread(
                    new SenderThread(queue, WARMUP_MESSAGES, SERVER_BASE_URL, metrics, warmupLatch),
                    "warmup-" + i);
            t.start();
        }

        warmupLatch.await();  // block until all warmup threads finish
        long warmupMs = System.currentTimeMillis() - warmupStart;

        // Main phase: optimal thread count for remaining messages
        CountDownLatch mainLatch = new CountDownLatch(MAIN_THREADS);
        int msgsPerThread = mainTotal / MAIN_THREADS;
        int remainder     = mainTotal % MAIN_THREADS;  // handle uneven division

        for (int i = 0; i < MAIN_THREADS; i++) {
            // Give the remainder messages to the last thread
            int threadMsgs = (i == MAIN_THREADS - 1) ? msgsPerThread + remainder : msgsPerThread;
            Thread t = new Thread(
                    new SenderThread(queue, threadMsgs, SERVER_BASE_URL, metrics, mainLatch),
                    "sender-" + i);
            t.start();
        }

        mainLatch.await();  // block until all main threads finish

        metrics.stopTimer();
        metrics.printSummary();
    }
}
