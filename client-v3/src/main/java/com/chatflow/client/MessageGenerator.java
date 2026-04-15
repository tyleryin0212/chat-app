package com.chatflow.client;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

public class MessageGenerator implements Runnable {

    private static final String[] MESSAGE_POOL = {
        "Hello!", "How are you?", "Nice to meet you!", "How you doing?", "Cool!",
        "Good to see you!", "What's up?", "Hey there!", "Good morning!", "Good evening!",
        "How's it going?", "Long time no see!", "Great to chat!", "Welcome!", "Goodbye!",
        "See you later!", "Thanks!", "You're welcome!", "Sounds good!", "I agree!",
        "That's interesting!", "Tell me more!", "Really?", "No way!", "Absolutely!",
        "Of course!", "Let me think about it.", "I'll get back to you.", "Cheers!", "Take care!",
        "Have a great day!", "Talk soon!", "LOL!", "That's funny!", "I'm doing well!",
        "Not bad!", "Pretty good!", "Can't complain!", "Busy day today.", "Same here!",
        "Me too!", "I understand.", "Good point!", "Well said!", "Exactly!",
        "Right on!", "For sure!", "Definitely!", "Sounds like a plan!", "Let's do it!"
    };

    private final BlockingQueue<ChatMessage> queue;
    private final int totalMessages;

    public MessageGenerator(BlockingQueue<ChatMessage> queue, int totalMessages) {
        this.queue = queue;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        Random random = new Random();
        for (int i = 0; i < totalMessages; i++) {
            int    userId = random.nextInt(100000) + 1;
            int    roomId = random.nextInt(20) + 1;

            // 90% TEXT, 5% JOIN, 5% LEAVE
            int roll = random.nextInt(100);
            String messageType = roll < 90 ? "TEXT" : roll < 95 ? "JOIN" : "LEAVE";

            ChatMessage msg = new ChatMessage();
            msg.setUserId(String.valueOf(userId));
            msg.setUsername("user" + userId);
            msg.setMessage(MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)]);
            msg.setRoomId(String.valueOf(roomId));
            msg.setTimestamp(Instant.now().toString());
            msg.setMessageType(messageType);

            try {
                queue.put(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("MessageGenerator done. Generated: " + totalMessages);
    }
}