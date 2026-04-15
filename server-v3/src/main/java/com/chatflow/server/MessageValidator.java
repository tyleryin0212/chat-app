package com.chatflow.server;

import java.time.Instant;
import java.util.UUID;

public class MessageValidator {

    public static boolean validate(ChatMessage msg) {
        if (msg == null) return false;
        if (!validateMessageId(msg.getMessageId()))     return false;
        if (!validateRoomId(msg.getRoomId()))           return false;
        if (!validateUserId(msg.getUserId()))           return false;
        if (!validateUsername(msg.getUsername()))       return false;
        if (!validateMessage(msg.getMessage()))         return false;
        if (!validateTimestamp(msg.getTimestamp()))     return false;
        if (!validateMessageType(msg.getMessageType())) return false;
        if (!validateServerId(msg.getServerId()))       return false;
        if (!validateClientIp(msg.getClientIp()))       return false;
        return true;
    }

    private static boolean validateMessageId(UUID messageId) {
        return messageId != null;
    }

    private static boolean validateRoomId(String roomId) {
        if (roomId == null || roomId.isEmpty()) return false;
        try {
            int id = Integer.parseInt(roomId);
            return id >= 1 && id <= 20;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean validateUserId(String userId) {
        if (userId == null || userId.isBlank()) return false;
        try {
            int id = Integer.parseInt(userId);
            return id >= 1 && id <= 100000;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean validateUsername(String username) {
        return username != null && username.matches("[a-zA-Z0-9]{3,20}");
    }

    private static boolean validateMessage(String message) {
        return message != null && !message.isEmpty() && message.length() <= 500;
    }

    private static boolean validateTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return false;
        try { Instant.parse(timestamp); return true; }
        catch (Exception e) { return false; }
    }

    private static boolean validateMessageType(MessageType messageType) {
        return messageType != null;
    }

    private static boolean validateServerId(String serverId) {
        return serverId != null && !serverId.isBlank();
    }

    private static boolean validateClientIp(String clientIp) {
        return clientIp != null && !clientIp.isBlank();
    }
}
