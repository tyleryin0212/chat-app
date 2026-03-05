package com.chatflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

/**
 * Producer of the producer-consumer system
 */
public class MessageGenerator implements Runnable {

    //pool of 50 pre-defined messages
    private static final String[] MESSAGE_POOL = {
            "Hello everyone!", "How's it going?", "Good morning!", "Anyone here?",
            "Let's get started", "Great idea!", "I agree with that", "Sounds good to me",
            "Can we discuss this?", "Interesting point", "Thanks for sharing",
            "I have a question", "What do you think?", "That makes sense",
            "Could you clarify?", "Well said!", "I'm on board", "Let's move forward",
            "Any updates?", "Looking good!", "Need some help here", "Almost done",
            "Just checking in", "Quick question", "Got it, thanks!",
            "On my way", "Be right back", "See you soon", "Talk later",
            "Good call", "I'll look into it", "Makes sense to me", "Noted!",
            "Will do!", "Let me know", "Sounds like a plan", "I'm available",
            "Just a heads up", "No worries", "Keep it up!", "Nice work!",
            "Stay tuned", "Coming right up", "All clear", "Ready when you are",
            "On it!", "Perfect timing", "Great job!", "Almost there", "Done!"
    };

    //messageType distribution: 90% text, 5% join, 5% leave
    private static final String[] MESSAGE_TYPES = new String[20];
    static {
        for (int i = 0; i < 18; i++) MESSAGE_TYPES[i] = "TEXT";
        MESSAGE_TYPES[18] = "JOIN";
        MESSAGE_TYPES[19] = "LEAVE";
    }

    private final BlockingQueue<String> queue;
    private final int totalMessages;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    public MessageGenerator(BlockingQueue<String> queue, int totalMessages) {
        this.queue = queue;
        this.totalMessages = totalMessages;
    }

    //producer
    @Override
    public void run() {
        int generated = 0;
        while (generated < totalMessages) {
            try{
                String msg = buildMessage();
                queue.put(msg);
                generated++;
            }catch (Exception e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("Messages generated: " + generated);
    }

    private String buildMessage() throws Exception{
        int userId = random.nextInt(100000) + 1;

        ObjectNode msg = mapper.createObjectNode();
        msg.put("userId", String.valueOf(userId));
        msg.put("username",    "user" + userId);
        msg.put("message",     MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)]);
        msg.put("roomId",      String.valueOf(random.nextInt(20) + 1));
        msg.put("timestamp",   Instant.now().toString());
        msg.put("messageType", MESSAGE_TYPES[random.nextInt(MESSAGE_TYPES.length)]);
        msg.put("messageId", UUID.randomUUID().toString());

        return mapper.writeValueAsString(msg);
    }
}
