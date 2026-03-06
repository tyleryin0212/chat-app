package com.chatflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Sends ChatMessage to RabbitMQ.
 */
public class RabbitPublisher {

    private static final String EXCHANGE_NAME = "chat.exchange";
    private static final String EXCHANGE_TYPE = "topic";
    private static final int POOL_SIZE = 10;
    private static final int TOTAL_ROOMS = 20;

    private final Connection connection;
    private final BlockingQueue<Channel> channelPool;
    private final ObjectMapper mapper =  new ObjectMapper();
    private final String serverId;


    public RabbitPublisher(Connection connection, String serverId) throws Exception {
        this.connection = connection;
        this.serverId = serverId;
        this.channelPool = new ArrayBlockingQueue<>(POOL_SIZE);

        initExchangeAndQueues();
        fillChannelPool();
    }

    //---- Setup: declare exchange and bind one queue per room ----
    private void initExchangeAndQueues() throws Exception {
        //use a temporary channel just for setup, then close it
        try (Channel ch = connection.createChannel()) {
            //declare the topic exchange
            ch.exchangeDeclare(EXCHANGE_NAME, EXCHANGE_TYPE,  true);

            for (int i = 1; i <= TOTAL_ROOMS; i++) {
                String queueName = "room." + i + "." + serverId;
                String routingKey = "room." + i;

                //declare durable queue for each room
                ch.queueDeclare(queueName, true, false, false, null);

                //bind queue to exchange with routing key
                ch.queueBind(queueName, EXCHANGE_NAME, routingKey);
            }
        }
        System.out.println("RabbitMQ exchange and queues initialized");
    }

    private void fillChannelPool() throws Exception {
        for (int i = 0; i < POOL_SIZE; i++) {
            channelPool.add(connection.createChannel());
        }
        System.out.println("Channel pool filled with " +  POOL_SIZE + " channels");
    }

    // ---- publish: borrow a channel, publish, return the channel ----

    public void publish(ChatMessage msg) throws Exception {
        String routingKey = "room." + msg.getRoomId();
        byte[] body = mapper.writeValueAsBytes(msg);

        Channel ch = channelPool.take();
        try{
            ch.basicPublish(
                    EXCHANGE_NAME,
                    routingKey,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,
                    body
            );
        }finally {
            channelPool.put(ch); //always return channel
        }

    }

    public void close() throws Exception {
        for (Channel channel : channelPool) {
            if (channel.isOpen()){
                channel.close();
            }
        }
        if(connection.isOpen()){
            connection.close();
        }
        System.out.println("RabbitMQ connection closed");
    }
}
