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
        // args: totalMessages serverWsUrl metricsUrl mainThreads pipelineStatsUrl consumerStatsUrl
        int    totalMessages      = args.length > 0 ? Integer.parseInt(args[0]) : 500000;
        String serverWsUrl        = args.length > 1 ? args[1] : "ws://localhost:8080/chat/";
        String metricsUrl         = args.length > 2 ? args[2] : "http://localhost:8081/metrics";
        int    mainThreads        = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        String pipelineStatsUrl   = args.length > 4 ? args[4] : "http://localhost:8081/pipeline-stats";
        String consumerStatsUrl   = args.length > 5 ? args[5] : "http://localhost:9090/stats";

        System.out.println("Starting LoadTestClient-v3:");
        System.out.println("  totalMessages=" + totalMessages + " threads=" + mainThreads);
        System.out.println("  server=" + serverWsUrl);
        System.out.println("  metrics=" + metricsUrl);
        System.out.println("  pipelineStats=" + pipelineStatsUrl);
        System.out.println("  consumerStats=" + consumerStatsUrl);

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

        // ── Fetch pipeline stats from server and consumer ─────────────────────
        JsonNode serverStats   = fetchJson(pipelineStatsUrl);
        JsonNode consumerStats = fetchJson(consumerStatsUrl);

        long clientSent       = metrics.getSuccessCount();
        long srvReceived      = serverStats  != null ? serverStats.path("msgsReceived").asLong()          : -1;
        long srvEnqueued      = serverStats  != null ? serverStats.path("msgsEnqueued").asLong()          : -1;
        long srvFailedValid   = serverStats  != null ? serverStats.path("msgsFailedValidation").asLong()  : -1;
        long srvBufferFull    = serverStats  != null ? serverStats.path("msgsBufferFull").asLong()        : -1;
        long srvPublished     = serverStats  != null ? serverStats.path("msgsPublishedToRedis").asLong()  : -1;
        long srvPublishFailed = serverStats  != null ? serverStats.path("msgsPublishFailed").asLong()     : -1;
        long csmConsumed      = consumerStats != null ? consumerStats.path("msgsConsumedFromStream").asLong() : -1;
        long csmDuplicates    = consumerStats != null ? consumerStats.path("duplicatesSkipped").asLong()  : -1;
        long csmConsumeErrors = consumerStats != null ? consumerStats.path("consumeErrors").asLong()      : -1;
        long csmWritten       = consumerStats != null ? consumerStats.path("msgsWrittenToDB").asLong()    : -1;
        long csmDbErrors      = consumerStats != null ? consumerStats.path("dbWriteErrors").asLong()      : -1;

        System.out.println("\n========== Pipeline Summary ==========");
        System.out.printf("1. Client sent          : %,d%n", clientSent);
        System.out.printf("2. Server received      : %,d  (failedValidation=%,d  bufferFull=%,d)%n",
                srvReceived, srvFailedValid, srvBufferFull);
        System.out.printf("3. Enqueued to buffer   : %,d%n", srvEnqueued);
        System.out.printf("4. Published to Redis   : %,d  (publishFailed=%,d)%n", srvPublished, srvPublishFailed);
        System.out.printf("5. Consumed from stream : %,d  (duplicates=%,d  errors=%,d)%n",
                csmConsumed, csmDuplicates, csmConsumeErrors);
        System.out.printf("6. Written to DB        : %,d  (dbErrors=%,d)%n", csmWritten, csmDbErrors);
        System.out.println("--------------------------------------");
        long gap = clientSent - dbCount;
        System.out.printf("Gap (sent - in DB)      : %,d  (%.1f%%)%n",
                gap, clientSent > 0 ? (gap * 100.0 / clientSent) : 0);
        System.out.println("======================================");

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

    /** Fetches a URL and parses the response body as JSON. Returns null on any error. */
    private static JsonNode fetchJson(String url) {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ObjectMapper().readTree(response.body());
            }
            System.out.println("  [WARN] " + url + " returned status " + response.statusCode());
        } catch (Exception e) {
            System.out.println("  [WARN] Could not reach " + url + ": " + e.getMessage());
        }
        return null;
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
