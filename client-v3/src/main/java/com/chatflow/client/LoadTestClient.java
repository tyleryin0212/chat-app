package com.chatflow.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

public class LoadTestClient {

    private static final int QUEUE_CAPACITY = 1000;

    public static void main(String[] args) throws Exception {
        // args: totalMessages serverWsUrl metricsUrl mainThreads
        int          totalMessages = args.length > 0 ? Integer.parseInt(args[0]) : 500000;
        List<String> serverUrls   = Arrays.asList((args.length > 1 ? args[1] : "ws://localhost:8080/chat/").split(","));
        String       metricsUrl   = args.length > 2 ? args[2] : "http://localhost:8081/metrics";
        int          mainThreads  = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        System.out.println("Starting LoadTestClient-v3:");
        System.out.println("  totalMessages=" + totalMessages + " threads=" + mainThreads);
        System.out.println("  servers=" + serverUrls + " (" + serverUrls.size() + " instance(s))");
        System.out.println("  metrics=" + metricsUrl);

        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        MetricsCollector metrics = new MetricsCollector();

        Thread generatorThread = new Thread(
                new MessageGenerator(queue, totalMessages), "msg-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();

        metrics.startTimer();

        CountDownLatch sendLatch  = new CountDownLatch(mainThreads); // all threads done sending
        CountDownLatch closeLatch = new CountDownLatch(mainThreads); // all connections closed
        int msgsPerThread = totalMessages / mainThreads;
        int remainder = totalMessages % mainThreads;

        for (int i = 0; i < mainThreads; i++) {
            int threadMsgs = (i == mainThreads - 1) ? msgsPerThread + remainder : msgsPerThread;
            Thread t = new Thread(
                    new SenderThread(queue, threadMsgs, serverUrls, metrics, sendLatch, closeLatch),
                    "sender-" + i);
            t.start();
        }

        sendLatch.await();   // wait for all sending to complete
        metrics.stopTimer(); // timer covers only the send phase

        // ── Drain and DB polling both run in background ───────────────────────
        long dbWriteStart = System.currentTimeMillis();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<Long> dbFuture    = exec.submit(() -> waitForDbStable(metricsUrl));
        Future<Void> drainFuture = exec.submit(() -> { closeLatch.await(); return null; });
        exec.shutdown();

        // Wait for both, then print everything together
        long dbCount = dbFuture.get();
        drainFuture.get();
        long dbWriteElapsed = System.currentTimeMillis() - dbWriteStart;

        metrics.printSummary();

        long   clientSent  = metrics.getSuccessCount();
        long   gap         = clientSent - dbCount;
        double wallTimeSec = metrics.getTotalTimeMs() / 1000.0;

        System.out.println("\n========== DB Write Results ==========");
        System.out.printf("Messages sent       : %,d  (%,.0f msg/sec)%n", clientSent, clientSent / wallTimeSec);
        System.out.printf("Messages in DB      : %,d%n", dbCount);
        System.out.printf("Gap (sent - in DB)  : %,d  (%.1f%%)%n", gap, clientSent > 0 ? (gap * 100.0 / clientSent) : 0);
        System.out.printf("DB catchup time     : %.2f s%n", dbWriteElapsed / 1000.0);
        System.out.printf("DB write throughput : %.0f msg/sec%n",
                dbCount / ((metrics.getTotalTimeMs() + dbWriteElapsed) / 1000.0));
        System.out.println("======================================");
    }

    /**
     * Polls the metrics endpoint every 5 seconds until the DB count
     * stops growing (two consecutive equal readings = DB has caught up).
     */
    private static long waitForDbStable(String metricsUrl) {
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        long lastCount = -1;
        int stableRounds = 0;

        while (stableRounds < 2) {
            try {
                Thread.sleep(5000);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(metricsUrl))
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(resp.body());
                    long count = root.path("totalMessages").asLong();
                    System.out.printf("  DB count: %,d%n", count);
                    if (count == lastCount) stableRounds++;
                    else stableRounds = 0;
                    lastCount = count;
                }
            } catch (Exception e) {
                System.out.println("  Polling error: " + e.getMessage());
            }
        }
        return lastCount;
    }
}
