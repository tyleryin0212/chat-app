package com.chatflow.server;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;

public class HealthServer {

    private final HttpServer httpServer;

    public HealthServer(int port) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"healthy\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        httpServer.setExecutor(null); // use default executor
    }

    public void start() {
        httpServer.start();

    }

    public void stop() {
        httpServer.stop(0);
    }
}
