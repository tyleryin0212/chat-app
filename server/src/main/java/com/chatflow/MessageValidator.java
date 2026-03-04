package com.chatflow;

import java.time.Instant;
import java.util.Set;

public class MessageValidator {
    private static final Set<String> VALID_MESSAGE_TYPES = Set.of("TEXT", "JOIN", "LEAVE");

    private String errorMessage;

    public MessageValidator() {}

    public String getErrorMessage() { return errorMessage; }

    public boolean validate(ChatMessage msg) {
        if (msg == null) {
            errorMessage = "Message body is null";
            return false;
        }
        if (!validateUserId(msg.getUserId()))       return false;
        if (!validateUsername(msg.getUsername()))    return false;
        if (!validateMessage(msg.getMessage()))      return false;
        if (!validateTimestamp(msg.getTimestamp()))  return false;
        if (!validateMessageType(msg.getMessageType())) return false;
        return true;
    }

    private boolean validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            errorMessage = "userId is required";
            return false;
        }
        try {
            int id = Integer.parseInt(userId);
            if (id < 1 || id > 100000) {
                errorMessage = "userId must be between 1 and 100000, got: " + id;
                return false;  // ← must return false after setting the message
            }
        } catch (NumberFormatException e) {
            errorMessage = "userId must be a numeric string, got: " + userId;
            return false;      // ← same here
        }
        return true;
    }

    private boolean validateUsername(String username) {
        if (username == null || username.isBlank()) {
            errorMessage = "username is required";
            return false;
        }
        if (!username.matches("[a-zA-Z0-9]{3,20}")) {
            errorMessage = "username must be 3-20 alphanumeric characters, got: '" + username + "'";
            return false;
        }
        return true;
    }

    private boolean validateMessage(String message) {
        if (message == null || message.isEmpty()) {
            errorMessage = "message is required";
            return false;
        }
        if (message.length() > 500) {
            errorMessage = "message exceeds 500 characters (length=" + message.length() + ")";
            return false;
        }
        return true;
    }

    private boolean validateTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            errorMessage = "timestamp is required";
            return false;
        }
        try {
            Instant.parse(timestamp);
        } catch (Exception e) {
            errorMessage = "timestamp must be ISO-8601 format, got: " + timestamp;
            return false;
        }
        return true;
    }

    private boolean validateMessageType(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            errorMessage = "messageType is required";
            return false;
        }
        if (!VALID_MESSAGE_TYPES.contains(messageType)) {
            errorMessage = "messageType must be TEXT, JOIN, or LEAVE, got: '" + messageType + "'";
            return false;
        }
        return true;
    }
}
