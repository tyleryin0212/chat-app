package com.chatflow.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {

    private final AtomicLong successCount     = new AtomicLong(0);
    private final AtomicLong failCount        = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong reconnections    = new AtomicLong(0);
    private final AtomicLong receivedCount    = new AtomicLong(0);

    // Round-trip tracking: messages confirmed echoed back to the sender
    private final AtomicLong roundTripCount   = new AtomicLong(0);
    private final AtomicLong roundTripTotalMs = new AtomicLong(0);
    private final AtomicLong roundTripMaxMs   = new AtomicLong(0);
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    private long startTime;
    private long endTime;
    private volatile long firstRoundTripTime = 0;
    private volatile long lastRoundTripTime  = 0;

    public void recordSuccess()      { successCount.incrementAndGet(); }
    public void recordFailure()      { failCount.incrementAndGet(); }
    public void recordConnection()   { totalConnections.incrementAndGet(); }
    public void recordReconnection() { reconnections.incrementAndGet(); }
    public void recordReceived()     { receivedCount.incrementAndGet(); }

    public void recordRoundTrip(long latencyMs) {
        long now = System.currentTimeMillis();
        if (firstRoundTripTime == 0) firstRoundTripTime = now;
        lastRoundTripTime = now;
        roundTripCount.incrementAndGet();
        roundTripTotalMs.addAndGet(latencyMs);
        latencies.add(latencyMs);
        long prev;
        do { prev = roundTripMaxMs.get(); }
        while (latencyMs > prev && !roundTripMaxMs.compareAndSet(prev, latencyMs));
    }

    public long getReceivedCount()  { return receivedCount.get(); }
    public long getSuccessCount()   { return successCount.get(); }

    public void startTimer()      { startTime = System.currentTimeMillis(); }
    public void stopTimer()       { endTime   = System.currentTimeMillis(); }
    public long getTotalTimeMs()  { return endTime - startTime; }

    public void printSummary() {
        long wallTimeMs  = endTime - startTime;
        double wallTimeSec = wallTimeMs / 1000.0;

        long   rtCount   = roundTripCount.get();
        double rtWindowSec = (lastRoundTripTime > firstRoundTripTime)
                ? (lastRoundTripTime - firstRoundTripTime) / 1000.0
                : wallTimeSec;
        double rtThroughput = rtCount > 0 ? rtCount / rtWindowSec : 0;
        double rtAvgMs      = rtCount > 0 ? (double) roundTripTotalMs.get() / rtCount : 0;
        long   rtMaxMs      = roundTripMaxMs.get();
        long[] sorted       = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long   rtP99Ms      = sorted.length > 0 ? sorted[(int)(sorted.length * 0.99)] : 0;
        long   rtP50Ms      = sorted.length > 0 ? sorted[(int)(sorted.length * 0.50)] : 0;

        System.out.println("\n========== Load Test Results ==========");
        System.out.printf("Messages sent         : %,d%n", successCount.get());
        System.out.printf("Failed messages       : %,d%n", failCount.get());
        System.out.printf("Total runtime         : %.2f s%n", wallTimeSec);
        System.out.println("---------------------------------------");
        System.out.printf("Round-trip confirmed  : %,d  (%.1f%% of sent)%n",
                rtCount, successCount.get() > 0 ? rtCount * 100.0 / successCount.get() : 0);

        System.out.printf("Round-trip throughput : %,.0f msg/sec%n", rtThroughput);
        System.out.printf("p50 round-trip        : %d ms%n", rtP50Ms);
        System.out.printf("p99 round-trip        : %d ms%n", rtP99Ms);
        System.out.printf("Max round-trip        : %d ms%n", rtMaxMs);
        System.out.println("---------------------------------------");
        System.out.printf("Total connections     : %,d%n", totalConnections.get());
        System.out.printf("Reconnections         : %,d%n", reconnections.get());
        System.out.println("=======================================");
    }
}
