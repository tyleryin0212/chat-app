package com.chatflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

public class MessageGenerator implements Runnable {

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

    // 1 JOIN + 18 TEXT + 1 LEAVE = 20 messages per session
    private static final int TEXT_PER_SESSION = 18;
    private static final int MSGS_PER_SESSION = 20;

    private final BlockingQueue<List<String>> queue;
    private final int totalMessages;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    public MessageGenerator(BlockingQueue<List<String>> queue, int totalMessages) {
        this.queue = queue;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        int generated = 0;
        int totalSessions = totalMessages / MSGS_PER_SESSION;
        for (int s = 0; s < totalSessions && generated < totalMessages; s++) {
            try {
                int userId = random.nextInt(100000) + 1;
                String roomId = String.valueOf(random.nextInt(20) + 1);
                List<String> session = new ArrayList<>();
                session.add(buildMessage(userId, roomId, "JOIN"));
                for (int i = 0; i < TEXT_PER_SESSION; i++) {
                    session.add(buildMessage(userId, roomId, "TEXT"));
                }
                session.add(buildMessage(userId, roomId, "LEAVE"));
                queue.put(session);
                generated += MSGS_PER_SESSION;
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("MessageGenerator done. Generated: " + generated);
    }

    private String buildMessage(int userId, String roomId, String messageType) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("messageId",   UUID.randomUUID().toString());
        msg.put("userId",      String.valueOf(userId));
        msg.put("username",    "user" + userId);
        msg.put("message",     MESSAGE_POOL[random.nextInt(MESSAGE_POOL.length)]);
        msg.put("roomId",      roomId);
        msg.put("timestamp",   Instant.now().toString());
        msg.put("messageType", messageType);
        return mapper.writeValueAsString(msg);
    }
}
