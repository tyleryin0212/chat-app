package com.chatflow.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomConsumer implements Runnable {
    private final String roomId;
    private final Connection rabbitConnection;
    private final RoomSessionManager sessionManager;
    private final String serverId;

    public RoomConsumer(String roomId, Connection rabbitConnection, RoomSessionManager sessionManager,  String serverId) {
        this.roomId = roomId;
        this.rabbitConnection = rabbitConnection;
        this.sessionManager = sessionManager;
        this.serverId = serverId;
    }

    @Override
    public void run() {
        String queueName = "room." + roomId + "." + serverId;
        try {
            //each consumer thread gets its own channel
            Channel channel = rabbitConnection.createChannel();
            channel.basicQos(1);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                try {

                    // new message - broadcast and mark as processed
                    sessionManager.broadcast(roomId, message);

                    // acknowledge after successful broadcast
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                } catch (Exception e) {
                    System.out.println("Broadcast failed for room " + roomId + ": " + e.getMessage());
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                }
            };

            channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {});
            System.out.println("Room consumer started for room " + roomId);

            Thread.currentThread().join();

        } catch (Exception e) {
            System.out.println("Room consumer failed for room " + roomId + ": " + e.getMessage());
        }
    }
}
