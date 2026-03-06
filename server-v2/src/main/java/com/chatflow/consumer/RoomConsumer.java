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
    private final ObjectMapper mapper = new ObjectMapper();

    //Bounded set of processed messageIds to handle duplicates
    private final Set<String> processedIds = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 10000;
        }
    });

    public RoomConsumer(String roomId, Connection rabbitConnection, RoomSessionManager sessionManager) {
        this.roomId = roomId;
        this.rabbitConnection = rabbitConnection;
        this.sessionManager = sessionManager;
    }

    @Override
    public void run() {
        String queueName = "room." + roomId;
        try {
            //each consumer thread gets its own channel
            Channel channel = rabbitConnection.createChannel();
            channel.basicQos(1);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                try {

                    JsonNode node = mapper.readTree(message);
                    String messageId = node.get("messageId").asText();

                    if (!processedIds.contains(messageId)) {
                        //duplicate
                        System.out.println("Duplicate message detected, skipping: " + messageId);
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        return;
                    }

                    // new message - broadcast and mark as processed
                    sessionManager.broadcast(roomId, message);
                    processedIds.add(messageId);
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
