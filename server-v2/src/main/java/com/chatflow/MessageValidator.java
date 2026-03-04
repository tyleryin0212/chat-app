package com.chatflow;

import java.time.Instant;

import java.util.UUID;

public class MessageValidator {

    public MessageValidator() {}

    public boolean validate(ChatMessage msg) {
        if (msg == null) {
            System.out.println("Message body is null");
            return false;
        }
        if (!validateMessageId(msg.getMessageId()))  return false;
        if (!validateRoomId(msg.getRoomId()))        return false;
        if (!validateUserId(msg.getUserId()))        return false;
        if (!validateUsername(msg.getUsername()))    return false;
        if (!validateMessage(msg.getMessage()))      return false;
        if (!validateTimestamp(msg.getTimestamp()))  return false;
        if (!validateMessageType(msg.getMessageType())) return false;
        if (!validateServerId(msg.getServerId()))  return false;
        if (!validateClientIp(msg.getClientIp()))  return false;
        return true;
    }



    private boolean validateMessageId(UUID messageId) {
        if (messageId == null) {
            System.out.println("Message id is required.");
            return false;
        }
        return true;
    }

    private boolean validateRoomId(String roomId) {
        if  (roomId == null || roomId.isEmpty()) {
            System.out.println("Room Id is required.");
            return false;
        }
        try {
            int id = Integer.parseInt(roomId);
            if (id < 1 || id > 20) {
                System.out.println("Invalid Room Id.");
                return false;
            }
        } catch (NumberFormatException e) {
            System.out.println("roomId must be a numeric string, got: " + roomId);
            return false;
        }
        return true;
    }

    private boolean validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            System.out.println("userId is required");
            return false;
        }

        try {
            int id = Integer.parseInt(userId);
            if (id < 1 || id > 100000) {
                System.out.println("userId must be between 1 and 100000, got: " + id);
                return false;
            }
        } catch (NumberFormatException e) {
            System.out.println("userId must be a numeric string, got: " + userId);
            return false;
        }
        return true;
    }

    private boolean validateUsername(String username) {
        if (username == null || username.isBlank()) {
            System.out.println("username is required");
            return false;
        }
        if (!username.matches("[a-zA-Z0-9]{3,20}")) {
            System.out.println("username must be 3-20 alphanumeric characters, got: '" + username + "'");
            return false;
        }
        return true;
    }

    private boolean validateMessage(String message) {
        if (message == null || message.isEmpty()) {
            System.out.println("message is required");
            return false;
        }
        if (message.length() > 500) {
            System.out.println("message exceeds 500 characters (length=" + message.length() + ")");
            return false;
        }
        return true;
    }

    private boolean validateTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            System.out.println("timestamp is required");
            return false;
        }
        try {
            Instant.parse(timestamp);
        } catch (Exception e) {
            System.out.println("timestamp must be ISO-8601 format, got: " + timestamp);
            return false;
        }
        return true;
    }

    private boolean validateMessageType(MessageType messageType) {
        if (messageType == null) {
            System.out.println("messageType is required");
            return false;
        }
        return true;
    }

    private boolean validateServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            System.out.println("serverId is required");
            return false;
        }
        //TO DO: may need further validation

        return true;
    }

    private boolean validateClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            System.out.println("clientIp is required");
            return false;
        }

        //TO DO: may need further validation

        return true;
    }
}
