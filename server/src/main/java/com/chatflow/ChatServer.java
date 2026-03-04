package com.chatflow;

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

    private final ObjectMapper mapper = new ObjectMapper();

    //track active connections
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public ChatServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    // ---- WebSocketServer lifecycle callbacks ----
    @Override
    public void onOpen(WebSocket ws, ClientHandshake handshake) {
        int count = activeConnections.incrementAndGet();
        String roomId = extractRoomId(handshake.getResourceDescriptor());
        ws.setAttachment(roomId);
    }

    @Override
    public void onMessage(WebSocket ws, String rawMessage) {
        try {
            //parse incoming JSON
            ChatMessage msg = mapper.readValue(rawMessage, ChatMessage.class);
            MessageValidator validator = new MessageValidator();

            //validate
            if (validator.validate(msg)) {
                // build and send success echo response
                String response = buildSuccessResponse(msg);
                ws.send(response);
            }
            else {
                //send error response
                String response = buildErrorResponse(validator.getErrorMessage());
                ws.send(response);
            }
        } catch (Exception e) {
            //JSON error
            ws.send(buildErrorResponse("Invalid JSON: " + e.getMessage()));
        }
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason, boolean remote) {
        //no ops
    }

    @Override
    public void onError(WebSocket ws, Exception e) {
        String addr = (ws != null) ? ws.getRemoteSocketAddress().toString() : "unknown";
    }

    @Override
    public void onStart() {
        //no ops
    }

    /**
     * Builds a JSON success response echoing the original message
     * plus a server-side timestamp.
     */
    private String extractRoomId(String resourceDescriptor) {
        //"/chat/{room12}"
        if (resourceDescriptor != null && resourceDescriptor.startsWith("/chat/")) {
            String roomId = resourceDescriptor.substring("/chat/".length());
            return roomId.isEmpty() ? "unknown" : roomId;
        }
        return "unknown";
    }

    /**
     * Builds a JSON error response with the given reason.
     */
    private String buildSuccessResponse(ChatMessage msg) throws Exception {
        ObjectNode response = mapper.createObjectNode();
        response.put("status", "ok");
        response.put("serverTimestamp", Instant.now().toString());

        ObjectNode echo = mapper.valueToTree(msg);
        response.set("echo", echo);
        return mapper.writeValueAsString(response);
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

    // ---- Entry point ----

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        ChatServer server = new ChatServer(port);
        server.start();

        HealthServer healthServer = new HealthServer(8081);
        healthServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1000);
                healthServer.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // Keep the main thread alive
        Thread.currentThread().join();
    }



}
