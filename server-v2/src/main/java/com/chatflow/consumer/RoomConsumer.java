package com.chatflow.consumer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;

public class RoomConsumer implements Runnable {
    private final String roomId;
    private final Connection rabbitConnection;
    private final RoomSessionManager sessionManager;

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
