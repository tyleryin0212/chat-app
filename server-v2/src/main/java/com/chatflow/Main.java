package com.chatflow;

import com.chatflow.consumer.RoomConsumer;
import com.chatflow.consumer.RoomSessionManager;
import com.chatflow.server.ChatServer;
import com.chatflow.server.HealthServer;
import com.chatflow.server.RabbitPublisher;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class Main {

    private static final int TOTAL_ROOMS = 20;

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        // ── RabbitMQ connection ───

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");   // replace with EC2 IP when deploying
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setPort(5672);
        Connection rabbitConnection = factory.newConnection();
        System.out.println("Connected to RabbitMQ");

        // ── Shared session registry ───

        RoomSessionManager sessionManager = new RoomSessionManager();

        // Create RabbitPublisher
        RabbitPublisher rabbitPublisher = new RabbitPublisher(rabbitConnection);

        // ── Start 20 consumer threads (one per room) ─────────────────────────
        for (int room = 1; room <= TOTAL_ROOMS; room++) {
            String roomId = String.valueOf(room);
            Thread t = new Thread(
                    new RoomConsumer(roomId, rabbitConnection, sessionManager),
                    "consumer-room-" + roomId
            );
            t.setDaemon(true);
            t.start();
        }
        System.out.println("All " + TOTAL_ROOMS + " room consumers started");

        // ── Start WebSocket server ────

        ChatServer server = new ChatServer(port, rabbitPublisher, sessionManager);
        server.start();

        // ── Start health endpoint ───
        HealthServer healthServer = new HealthServer(8081);
        healthServer.start();

        System.out.println("ChatFlow server started on port " + port);

        // ── Shutdown hook ────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Shutting down...");
                server.stop(1000);
                healthServer.stop();
                rabbitPublisher.close();
                rabbitConnection.close();
            } catch (Exception e) {
                System.out.println("Error during shutdown: " + e.getMessage());
            }
        }));

        // Keep main thread alive
        Thread.currentThread().join();
    }
}
