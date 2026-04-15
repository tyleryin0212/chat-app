package com.chatflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SenderThread implements Runnable {

    private static final int MAX_RETRIES = 5;
    private static final int DRAIN_MS = 30000;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BlockingQueue<List<String>> queue;
    private final int messagesToSend;
    private final List<String> serverUrls;
    private final MetricsCollector metrics;
    private final CountDownLatch sendLatch;
    private final CountDownLatch closeLatch;
    private final Map<String, WebSocketClient> connections = new HashMap<>();
    private final Random random = new Random();

    // messageId → send timestamp; used to measure round-trip latency.
    // ConcurrentHashMap because onMessage fires on a WebSocket selector thread,
    // not the sender thread.
    private final ConcurrentHashMap<String, Long> sentAt = new ConcurrentHashMap<>();

    public SenderThread(BlockingQueue<List<String>> queue, int messagesToSend, List<String> serverUrls,
                        MetricsCollector metrics, CountDownLatch sendLatch, CountDownLatch closeLatch) {
        this.queue = queue;
        this.messagesToSend = messagesToSend;
        this.serverUrls = Collections.unmodifiableList(serverUrls);
        this.metrics = metrics;
        this.sendLatch = sendLatch;
        this.closeLatch = closeLatch;
    }

    private String randomUrl() {
        return serverUrls.get(random.nextInt(serverUrls.size()));
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
                    ws = getOrCreateConnection(roomId, randomUrl());
                } catch (Exception e) {
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
            sendLatch.countDown();
            try { Thread.sleep(DRAIN_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            closeAllConnections();
            closeLatch.countDown();
        }
    }

    private WebSocketClient getOrCreateConnection(String roomId, String baseUrl) throws InterruptedException {
        WebSocketClient ws = connections.get(roomId);
        if (ws == null || ws.isClosed()) {
            ws = createConnection(roomId, baseUrl);
            connections.put(roomId, ws);
        }
        return ws;
    }

    private WebSocketClient createConnection(String roomId, String baseUrl) throws InterruptedException {
        URI uri = URI.create(baseUrl + roomId);
        CountDownLatch connectLatch = new CountDownLatch(1);
        WebSocketClient ws = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake h) { connectLatch.countDown(); }
            @Override public void onMessage(String message) {
                metrics.recordReceived();
                String msgId = extractMessageId(message);
                if (!msgId.isEmpty()) {
                    Long sendTime = sentAt.remove(msgId);
                    if (sendTime != null) {
                        metrics.recordRoundTrip(System.currentTimeMillis() - sendTime);
                    }
                }
            }
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
                // Record send time just before sending so the clock starts
                // as close to the actual network write as possible.
                String msgId = extractMessageId(msg);
                if (!msgId.isEmpty()) {
                    sentAt.put(msgId, System.currentTimeMillis());
                }
                ws.send(msg);
                return true;
            } catch (Exception e) {
                Thread.sleep((long) Math.pow(2, attempt) * 100);
                try {
                    ws = createConnection(roomId, randomUrl());
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
            return mapper.readTree(rawMsg).get("roomId").asText();
        } catch (Exception e) { return "1"; }
    }

    private String extractMessageId(String rawMsg) {
        try {
            return mapper.readTree(rawMsg).path("messageId").asText("");
        } catch (Exception e) { return ""; }
    }
}
