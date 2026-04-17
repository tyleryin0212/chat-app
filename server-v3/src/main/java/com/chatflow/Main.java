package com.chatflow;

import com.chatflow.consumer.RoomSessionManager;
import com.chatflow.server.AdminServer;
import com.chatflow.server.ChatServer;
import com.chatflow.server.ChatMessage;
import com.chatflow.server.MetricsService;
import com.chatflow.server.RedisPublisher;
import com.chatflow.server.RedisPublisherWorker;
import com.chatflow.server.RedisSubscriber;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Main {

    public static void main(String[] args) throws Exception {
        // args: port serverId redisHost dbHost
        int port        = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String serverId = args.length > 1 ? args[1] : "server-1";
        String redisHost = args.length > 2 ? args[2] : "localhost";
        String dbHost   = args.length > 3 ? args[3] : "54.188.60.194";

        System.out.println("Starting server-v3 | serverId=" + serverId + " | redisHost=" + redisHost + " | dbHost=" + dbHost);

        // ── Shared session registry ───────────────────────────────────────────
        RoomSessionManager sessionManager = new RoomSessionManager();

        // ── Redis publisher (Pub/Sub fan-out + Stream persistence) ────────────
        RedisPublisher redisPublisher = new RedisPublisher(redisHost, 6379);

        // ── Redis subscriber (local fan-out to WebSocket clients) ─────────────
        RedisSubscriber redisSubscriber = new RedisSubscriber(redisHost, 6379, sessionManager);
        redisSubscriber.start();

        // ── Metrics service (queries EC2 PostgreSQL) ──────────────────────────
        MetricsService metricsService = new MetricsService(dbHost, redisHost);

        // ── Publish buffer + worker threads ───────────────────────────────────
        BlockingQueue<ChatMessage> publishBuffer = new LinkedBlockingQueue<>(1000_000);
        int numPublisherWorkers = 4;
        for (int i = 0; i < numPublisherWorkers; i++) {
            Thread t = new Thread(new RedisPublisherWorker(publishBuffer, redisPublisher, "redis-publisher-" + i), "redis-publisher-" + i);
            t.setDaemon(true);
            t.start();
        }
        System.out.println("Started " + numPublisherWorkers + " Redis publisher workers");

        // ── WebSocket server ──────────────────────────────────────────────────
        ChatServer server = new ChatServer(port, publishBuffer, sessionManager, serverId);
        server.start();

        // ── Health + metrics endpoint ─────────────────────────────────────────
        int adminPort = port + 1;
        AdminServer adminServer = new AdminServer(adminPort, metricsService);
        adminServer.start();

        System.out.println("ChatFlow server-v3 started on port " + port);
        System.out.println("Metrics API: GET http://localhost:" + adminPort + "/metrics");

        // ── Shutdown hook ─────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Shutting down...");
                server.stop(1000);
                adminServer.stop();
                redisSubscriber.stop();
                redisPublisher.close();
            } catch (Exception e) {
                System.out.println("Error during shutdown: " + e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }
}