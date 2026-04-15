package com.chatflow.server;

import com.chatflow.consumer.RoomSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<ChatMessage> publishBuffer;
    private final RoomSessionManager sessionManager;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final String serverId;

    public ChatServer(int port, BlockingQueue<ChatMessage> publishBuffer, RoomSessionManager sessionManager, String serverId) {
        super(new InetSocketAddress(port));
        this.publishBuffer = publishBuffer;
        this.sessionManager = sessionManager;
        this.serverId = serverId;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket ws, ClientHandshake handshake) {
        int count = activeConnections.incrementAndGet();
        String roomId = extractRoomId(handshake.getResourceDescriptor());
        ws.setAttachment(roomId);
        sessionManager.addSession(roomId, ws);
        log.info("New connection | room={} | totalActive={}", roomId, count);
    }

    @Override
    public void onMessage(WebSocket ws, String rawMessage) {
        try {
            ChatMessage msg = mapper.readValue(rawMessage, ChatMessage.class);
            msg.setServerId(this.serverId);
            msg.setClientIp(ws.getRemoteSocketAddress().getAddress().getHostAddress());
            if (MessageValidator.validate(msg)) {
                if (!publishBuffer.offer(msg)) {
                    trySend(ws, buildErrorResponse("Server busy — publish buffer full"));
                }
            } else {
                trySend(ws, buildErrorResponse("Message validation failed"));
            }
        } catch (Exception e) {
            trySend(ws, buildErrorResponse("Invalid JSON: " + e.getMessage()));
        }
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason, boolean remote) {
        int count = activeConnections.decrementAndGet();
        String roomId = ws.getAttachment();
        if (roomId != null) sessionManager.removeSession(roomId, ws);
        log.info("Connection closed | code={} | totalActive={}", code, count);
    }

    @Override
    public void onError(WebSocket ws, Exception e) {
        String addr = (ws != null) ? ws.getRemoteSocketAddress().toString() : "unknown";
        log.error("Error on connection {}: {}", addr, e.getMessage());
    }

    @Override
    public void onStart() {
        log.info("ChatFlow WebSocket server started on port {}", getPort());
    }

    private String extractRoomId(String resourceDescriptor) {
        if (resourceDescriptor != null && resourceDescriptor.startsWith("/chat/")) {
            String roomId = resourceDescriptor.substring("/chat/".length());
            return roomId.isEmpty() ? "unknown" : roomId;
        }
        return "unknown";
    }

    private void trySend(WebSocket ws, String message) {
        try {
            if (ws != null && ws.isOpen()) ws.send(message);
        } catch (Exception ignored) {}
    }

    private String buildErrorResponse(String reason) {
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("status", "error");
            response.put("reason", reason);
            response.put("serverTimestamp", Instant.now().toString());
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"reason\":\"internal error\"}";
        }
    }
}
