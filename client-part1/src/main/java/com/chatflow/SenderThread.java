package com.chatflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Consumer of the Producer-Consumer system
 */
public class SenderThread implements Runnable {
    private static final int MAX_RETRIES = 5;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BlockingQueue<String> queue;
    private final int messagesToSend; // message number this thread is responsible for
    private final String serverBaseUrl;
    private final MetricsCollector metrics;
    private final CountDownLatch latch; // notify loadTestClient this thread is done

    //connection: roomId -> webSocketClient
    private final Map<String, WebSocketClient> connections = new HashMap<>();

    public SenderThread(BlockingQueue<String> queue, int messagesToSend, String serverBaseUrl,
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

        try {
            while (sent < messagesToSend) {
                String rawMsg = queue.take();
                String roomId = extractRoomId(rawMsg);
                WebSocketClient ws = getOrCreateConnection(roomId);

                boolean success = sendWithRetry(ws, rawMsg, roomId);
                if (success) {
                    metrics.recordSuccess();
                }
                else {
                    metrics.recordFailure();
                }
                sent++;
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
            @Override public void onOpen(ServerHandshake handshake) {
                connectLatch.countDown();  // unblock createConnection()
            }
            @Override public void onMessage(String message) {

            }
            @Override public void onClose(int code, String reason, boolean remote) {

            }
            @Override public void onError(Exception ex) {

            }
        };

        ws.connect();
        connectLatch.await();  // wait until onOpen fires before returning
        metrics.recordConnection();

        return ws;
    }

    private void closeAllConnections() {
        connections.forEach((roomId, ws) -> {
            if (!ws.isClosed()) {
                ws.close();
            }
        });
    }

    private boolean sendWithRetry(WebSocketClient ws, String msg, String roomId) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                ws.send(msg);
                return true;
            } catch (Exception e) {


                // Exponential backoff: 100ms, 200ms, 400ms, 800ms
                Thread.sleep((long) Math.pow(2, attempt) * 100);

                // Try to reconnect before next attempt

                    ws = createConnection(roomId);
                    connections.put(roomId, ws);
                    metrics.recordReconnection();

            }
        }
        return false;  // all retries exhausted
    }

    private String extractRoomId(String rawMsg) {
        try {
            JsonNode node = mapper.readTree(rawMsg);
            return node.get("roomId").asText();
        } catch (Exception e) {
            return "1";  // fallback to room 1 if parsing fails
        }
    }


}
