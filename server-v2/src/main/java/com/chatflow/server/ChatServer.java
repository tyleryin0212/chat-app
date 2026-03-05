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
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);
    private final ObjectMapper mapper = new ObjectMapper();

    //rabbitMQ publisher
    private final RabbitPublisher rabbitPublisher;

    // shared session registry with consumer
    private final RoomSessionManager sessionManager;

    //track active connections
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public ChatServer(int port, RabbitPublisher rabbitPublisher,  RoomSessionManager sessionManager) {
        super(new InetSocketAddress(port));
        this.rabbitPublisher = rabbitPublisher;
        this.sessionManager = sessionManager;
        setReuseAddr(true);
    }

    // ---- WebSocketServer lifecycle callbacks ----
    @Override
    public void onOpen(WebSocket ws, ClientHandshake handshake) {
        int count = activeConnections.incrementAndGet();
        String roomId = extractRoomId(handshake.getResourceDescriptor());
        ws.setAttachment(roomId);
        //register client session in room
        sessionManager.addSession(roomId, ws);
        log.info("New connection from {} | room={} | totalActive={}",
                ws.getRemoteSocketAddress(), roomId, count);
    }

    @Override
    public void onMessage(WebSocket ws, String rawMessage) {
        try {
            //parse incoming JSON
            ChatMessage msg = mapper.readValue(rawMessage, ChatMessage.class);

            //add server metadata
            msg.setServerId("server-1");
            msg.setClientIp(ws.getRemoteSocketAddress().getAddress().getHostAddress());

            //validate
            if (MessageValidator.validate(msg)) {
                // send message to RabbitMQ
                rabbitPublisher.publish(msg);
            }
            else {
                //send error response
                ws.send(buildErrorResponse("Message validation failed"));
            }
        } catch (Exception e) {
            //JSON error
            ws.send(buildErrorResponse("Invalid JSON: " + e.getMessage()));
        }
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason, boolean remote) {
        int count = activeConnections.decrementAndGet();
        String roomId = ws.getAttachment();
        if (roomId != null) {
            sessionManager.removeSession(roomId, ws);
        }
        log.info("Connection closed from {} | code={} | reason={} | totalActive={}",
                ws.getRemoteSocketAddress(), code, reason, count);
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

    /**
     * Builds a JSON success response echoing the original message
     * plus a server-side timestamp.
     */
    private String extractRoomId(String resourceDescriptor) {

        if (resourceDescriptor != null && resourceDescriptor.startsWith("/chat/")) {
            String roomId = resourceDescriptor.substring("/chat/".length());
            return roomId.isEmpty() ? "unknown" : roomId;
        }
        return "unknown";
    }


    /**
     * Builds a JSON error response with the given reason.
     */
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
