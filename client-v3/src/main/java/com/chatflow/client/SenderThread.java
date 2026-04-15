package com.chatflow.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SenderThread implements Runnable {

    private static final int MAX_RETRIES = 5;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BlockingQueue<List<String>> queue;
    private final int messagesToSend;
    private final String serverBaseUrl;
    private final MetricsCollector metrics;
    private final CountDownLatch latch;
    private final Map<String, WebSocketClient> connections = new HashMap<>();

    public SenderThread(BlockingQueue<List<String>> queue, int messagesToSend, String serverBaseUrl,
                        MetricsCollector metrics, CountDownLatch latch) {
        this.queue = queue;
        this.messagesToSend = messagesToSend;
        this.serverBaseUrl = serverBaseUrl;
        this.metrics = metrics;
        this.latch = latch;
    }

    @Override
    public void run() {
        int sent = 0;
        String threadName = Thread.currentThread().getName();
        try {
            while (sent < messagesToSend) {
                List<String> session = queue.take();
                String roomId = extractRoomId(session.get(0));
                WebSocketClient ws = null;
                try {
                    ws = getOrCreateConnection(roomId);
                } catch (Exception e) {
                    // connection timed out — count session as failed and move on
                    metrics.recordFailure();
                    sent += session.size();
                    continue;
                }
                for (String message : session) {
                    if (sendWithRetry(ws, message, roomId)) metrics.recordSuccess();
                    else metrics.recordFailure();
                }
                sent += session.size();
                if (sent % 10000 == 0) {
                    System.out.printf("[%s] sent %d / %d%n", threadName, sent, messagesToSend);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeAllConnections();
            latch.countDown();
        }
    }

    private WebSocketClient getOrCreateConnection(String roomId) throws InterruptedException {
        WebSocketClient ws = connections.get(roomId);
        if (ws == null || ws.isClosed()) {
            ws = createConnection(roomId);
            connections.put(roomId, ws);
        }
        return ws;
    }

    private WebSocketClient createConnection(String roomId) throws InterruptedException {
        URI uri = URI.create(serverBaseUrl + roomId);
        CountDownLatch connectLatch = new CountDownLatch(1);
        WebSocketClient ws = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake h) { connectLatch.countDown(); }
            @Override public void onMessage(String message) { metrics.recordReceived(); }
            @Override public void onClose(int code, String reason, boolean remote) {}
            @Override public void onError(Exception ex) {}
        };
        ws.setConnectionLostTimeout(0);
        ws.connect();
        if (!connectLatch.await(10, TimeUnit.SECONDS)) {
            ws.close();
            throw new RuntimeException("WebSocket connection timeout for room " + roomId);
        }
        metrics.recordConnection();
        return ws;
    }

    private boolean sendWithRetry(WebSocketClient ws, String msg, String roomId) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                ws.send(msg);
                return true;
            } catch (Exception e) {
                Thread.sleep((long) Math.pow(2, attempt) * 100);
                try {
                    ws = createConnection(roomId);
                    connections.put(roomId, ws);
                    metrics.recordReconnection();
                } catch (Exception ex) {
                    System.out.println("Reconnect failed for room " + roomId);
                }
            }
        }
        return false;
    }

    private void closeAllConnections() {
        connections.forEach((roomId, ws) -> {
            if (!ws.isClosed()) {
                try { ws.closeBlocking(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
    }

    private String extractRoomId(String rawMsg) {
        try {
            JsonNode node = mapper.readTree(rawMsg);
            return node.get("roomId").asText();
        } catch (Exception e) { return "1"; }
    }
}
