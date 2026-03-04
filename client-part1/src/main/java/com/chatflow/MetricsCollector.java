package com.chatflow;

import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {
    private final AtomicLong successCount    = new AtomicLong(0);
    private final AtomicLong failCount       = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong reconnections   = new AtomicLong(0);

    private long startTime;
    private long endTime;

    public void recordSuccess()      { successCount.incrementAndGet(); }
    public void recordFailure()      { failCount.incrementAndGet(); }
    public void recordConnection()   { totalConnections.incrementAndGet(); }
    public void recordReconnection() { reconnections.incrementAndGet(); }

    public void startTimer() { startTime = System.currentTimeMillis(); }
    public void stopTimer()  { endTime   = System.currentTimeMillis(); }

    public long getSuccessCount() { return successCount.get(); }
    public long getFailCount()    { return failCount.get(); }

    public void printSummary() {
        long wallTimeMs = endTime - startTime;
        double wallTimeSec = wallTimeMs / 1000.0;
        double throughput = successCount.get() / wallTimeSec;

        System.out.println("\n========== Load Test Results ==========");
        System.out.printf("Successful messages : %,d%n", successCount.get());
        System.out.printf("Failed messages     : %,d%n", failCount.get());
        System.out.printf("Total runtime       : %.2f s%n", wallTimeSec);
        System.out.printf("Throughput          : %.0f msg/sec%n", throughput);
        System.out.printf("Total connections   : %,d%n", totalConnections.get());
        System.out.printf("Reconnections       : %,d%n", reconnections.get());
        System.out.println("=======================================");
    }
}
