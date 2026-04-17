package com.chatflow.server;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class AdminServer {

    private final HttpServer httpServer;

    public AdminServer(int port, MetricsService metricsService) throws Exception {
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

        // ── GET /api/messages?roomId=X ────────────────────────────────────────
        // Returns 100 most recent messages for the given room (Redis-cached, 10s TTL).
        httpServer.createContext("/api/messages", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String roomId = "1";
                String query = exchange.getRequestURI().getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2 && "roomId".equals(kv[0])) {
                            roomId = kv[1];
                        }
                    }
                }
                String json = metricsService.getRecentMessages(roomId);
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

        httpServer.setExecutor(Executors.newFixedThreadPool(100));
    }

    public void start() { httpServer.start(); }
    public void stop()  { httpServer.stop(0); }
}