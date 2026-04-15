package com.chatflow.client;

import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {

    private final AtomicLong successCount     = new AtomicLong(0);
    private final AtomicLong failCount        = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong reconnections    = new AtomicLong(0);
    private final AtomicLong receivedCount    = new AtomicLong(0);

    private long startTime;
    private long endTime;
    private volatile long firstReceivedTime = 0;
    private volatile long lastReceivedTime  = 0;

    public void recordSuccess()      { successCount.incrementAndGet(); }
    public void recordFailure()      { failCount.incrementAndGet(); }
    public void recordConnection()   { totalConnections.incrementAndGet(); }
    public void recordReconnection() { reconnections.incrementAndGet(); }
    public void recordReceived() {
        long now = System.currentTimeMillis();
        if (firstReceivedTime == 0) firstReceivedTime = now;
        lastReceivedTime = now;
        receivedCount.incrementAndGet();
    }

    public long getReceivedCount() { return receivedCount.get(); }

    public void startTimer()      { startTime = System.currentTimeMillis(); }
    public void stopTimer()       { endTime   = System.currentTimeMillis(); }
    public long getTotalTimeMs()  { return endTime - startTime; }

    public void printSummary() {
        long wallTimeMs = endTime - startTime;
        double wallTimeSec = wallTimeMs / 1000.0;
        double sendThroughput = successCount.get() / wallTimeSec;

        // Delivery throughput: total fan-out deliveries / time from test start to last received
        long deliveryWindowMs = (lastReceivedTime > startTime) ? (lastReceivedTime - startTime) : wallTimeMs;
        double deliveryThroughput = receivedCount.get() / (deliveryWindowMs / 1000.0);
        double fanOutRatio = successCount.get() > 0 ? (double) receivedCount.get() / successCount.get() : 0;

        System.out.println("\n========== Load Test Results ==========");
        System.out.printf("Messages sent       : %,d%n", successCount.get());
        System.out.printf("Failed messages     : %,d%n", failCount.get());
        System.out.printf("Total runtime       : %.2f s%n", wallTimeSec);
        System.out.println("---------------------------------------");
        System.out.printf("Send throughput     : %,.0f msg/sec%n", sendThroughput);
        System.out.printf("Messages delivered  : %,d%n", receivedCount.get());
        System.out.printf("Fan-out ratio       : %.1fx%n", fanOutRatio);
        System.out.printf("Delivery throughput : %,.0f msg/sec%n", deliveryThroughput);
        System.out.println("---------------------------------------");
        System.out.printf("Total connections   : %,d%n", totalConnections.get());
        System.out.printf("Reconnections       : %,d%n", reconnections.get());
        System.out.println("=======================================");
    }
}
