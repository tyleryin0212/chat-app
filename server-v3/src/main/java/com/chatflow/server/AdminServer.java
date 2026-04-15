package com.chatflow.server;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class AdminServer {

    private final HttpServer httpServer;

    public AdminServer(int port, MetricsService metricsService, Supplier<String> pipelineStatsSupplier) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(port), 100);

        // ── GET /health ───────────────────────────────────────────────────────
        httpServer.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"healthy\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
        });

        // ── GET /metrics ──────────────────────────────────────────────────────
        // Called by the client after load test completes to log analytics results.
        httpServer.createContext("/metrics", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String json = metricsService.getMetrics();
                byte[] response = json.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
            } catch (Exception e) {
                byte[] response = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes();
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
            }
        });

        // ── GET /pipeline-stats ───────────────────────────────────────────────
        // Returns server-side pipeline counters: received → enqueued → published to Redis.
        httpServer.createContext("/pipeline-stats", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                byte[] response = pipelineStatsSupplier.get().getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
            } catch (Exception e) {
                byte[] response = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes();
                exchange.sendResponseHeaders(500, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
            }
        });

        httpServer.setExecutor(Executors.newFixedThreadPool(20));
    }

    public void start() { httpServer.start(); }
    public void stop()  { httpServer.stop(0); }
}