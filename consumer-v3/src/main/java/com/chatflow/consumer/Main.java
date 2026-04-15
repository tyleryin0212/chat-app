package com.chatflow.consumer;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final int TOTAL_ROOMS = 20;
    private static final int NUM_WRITERS = 5;

    public static void main(String[] args) throws Exception {
        // args: redisHost dbHost batchSize flushIntervalMs
        String redisHost     = args.length > 0 ? args[0] : "localhost";
        String dbHost        = args.length > 1 ? args[1] : "54.188.60.194";
        int batchSize        = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        long flushIntervalMs = args.length > 3 ? Long.parseLong(args[3]) : 500;

        System.out.println("Starting consumer-v3:");
        System.out.println("  redisHost=" + redisHost + " dbHost=" + dbHost);
        System.out.println("  batchSize=" + batchSize + " flushInterval=" + flushIntervalMs + "ms");
        System.out.println("  writers=" + NUM_WRITERS);

        // ── Redis connection pool ─────────────────────────────────────────────
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(TOTAL_ROOMS + NUM_WRITERS + 5); // one per consumer + headroom
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);
        JedisPool jedisPool = new JedisPool(poolConfig, redisHost, 6379);
        System.out.println("Connected to Redis at " + redisHost);

        // ── Database connection pool — one connection per writer thread ────────
        DatabaseConnectionPool dbPool = new DatabaseConnectionPool(
                dbHost, "chatdb", "chatflow", "123456", NUM_WRITERS);

        // ── Shared write buffer — all consumers produce, all writers drain ─────
        BlockingQueue<ChatMessage> writeBuffer = new LinkedBlockingQueue<>(100_000);

        // ── Batch writer pool (write-behind) ──────────────────────────────────
        BatchWriter[] writers = new BatchWriter[NUM_WRITERS];
        for (int i = 0; i < NUM_WRITERS; i++) {
            writers[i] = new BatchWriter(dbPool, writeBuffer, batchSize, flushIntervalMs);
            Thread writerThread = new Thread(writers[i], "batch-writer-" + i);
            writerThread.setDaemon(true);
            writerThread.start();
        }
        System.out.println("Started " + NUM_WRITERS + " batch writer threads");

        // ── Stats aggregator ──────────────────────────────────────────────────
        ScheduledExecutorService statsPool = Executors.newScheduledThreadPool(1);
        statsPool.scheduleAtFixedRate(
                new StatsAggregator(writers, writeBuffer),
                StatsAggregator.intervalSec(), StatsAggregator.intervalSec(), TimeUnit.SECONDS);
        System.out.println("Stats aggregator started (interval=" + StatsAggregator.intervalSec() + "s)");

        // ── Start one stream consumer thread per room ─────────────────────────
        for (int room = 1; room <= TOTAL_ROOMS; room++) {
            String roomId = String.valueOf(room);
            Thread t = new Thread(
                    new RedisStreamConsumer(roomId, jedisPool, writeBuffer, "consumer-" + roomId),
                    "stream-consumer-room-" + roomId
            );
            t.setDaemon(true);
            t.start();
        }
        System.out.println("All " + TOTAL_ROOMS + " stream consumers started");

        // ── Shutdown hook ─────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Shutting down consumer-v3...");
                long totalWritten = 0, totalBatches = 0, totalErrors = 0;
                for (BatchWriter w : writers) {
                    totalWritten += w.getTotalWritten();
                    totalBatches += w.getTotalBatches();
                    totalErrors  += w.getWriteErrors();
                }
                System.out.printf("Final DB stats: written=%d batches=%d errors=%d%n",
                        totalWritten, totalBatches, totalErrors);
                statsPool.shutdown();
                jedisPool.close();
                dbPool.close();
            } catch (Exception e) {
                System.out.println("Error during shutdown: " + e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }
}