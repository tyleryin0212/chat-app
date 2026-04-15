package com.chatflow.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class LoadTestClient {

    private static final int QUEUE_CAPACITY = 1000;

    public static void main(String[] args) throws Exception {
        // args: totalMessages serverWsUrl metricsUrl mainThreads
        int    totalMessages = args.length > 0 ? Integer.parseInt(args[0]) : 500000;
        String serverWsUrl   = args.length > 1 ? args[1] : "ws://localhost:8080/chat/";
        String metricsUrl    = args.length > 2 ? args[2] : "http://localhost:8081/metrics";
        int    mainThreads   = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        System.out.println("Starting LoadTestClient-v3:");
        System.out.println("  totalMessages=" + totalMessages + " threads=" + mainThreads);
        System.out.println("  server=" + serverWsUrl);
        System.out.println("  metrics=" + metricsUrl);

        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        MetricsCollector metrics = new MetricsCollector();

        Thread generatorThread = new Thread(
                new MessageGenerator(queue, totalMessages), "msg-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();

        metrics.startTimer();

        CountDownLatch mainLatch = new CountDownLatch(mainThreads);
        int msgsPerThread = totalMessages / mainThreads;
        int remainder = totalMessages % mainThreads;

        for (int i = 0; i < mainThreads; i++) {
            int threadMsgs = (i == mainThreads - 1) ? msgsPerThread + remainder : msgsPerThread;
            Thread t = new Thread(
                    new SenderThread(queue, threadMsgs, serverWsUrl, metrics, mainLatch),
                    "sender-" + i);
            t.start();
        }

        mainLatch.await();
        metrics.stopTimer();
        metrics.printSummary();

        // ── Wait for DB writes to catch up ────────────────────────────────────
        System.out.println("\nWaiting for DB writes to complete...");
        long dbWriteStart = System.currentTimeMillis();
        long dbCount = waitForDbStable(metricsUrl);
        long dbWriteElapsed = System.currentTimeMillis() - dbWriteStart;

        System.out.println("\n========== DB Write Results ==========");
        System.out.printf("Messages in DB      : %,d%n", dbCount);
        System.out.printf("DB catchup time     : %.2f s%n", dbWriteElapsed / 1000.0);
        System.out.printf("DB write throughput : %.0f msg/sec%n",
                dbCount / ((metrics.getTotalTimeMs() + dbWriteElapsed) / 1000.0));
        System.out.println("======================================");

        // ── Fetch and log full metrics from server ────────────────────────────
        System.out.println("\n========== Fetching Metrics API ==========");
        fetchAndPrintMetrics(metricsUrl);
    }

    /**
     * Polls the metrics endpoint every 5 seconds until totalMessages count
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

    private static void fetchAndPrintMetrics(String url) {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("Metrics API response (status 200):");
                System.out.println(response.body());
            } else {
                System.out.println("Metrics API returned status " + response.statusCode());
                System.out.println(response.body());
            }
        } catch (Exception e) {
            System.out.println("Failed to fetch metrics: " + e.getMessage());
        }
        System.out.println("==========================================");
    }
}
