package com.chatflow;

/**
 * Represents an incoming chat message from a WebSocket client.

 */
public class ChatMessage {
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;


    public ChatMessage() {}

    public ChatMessage(String userId, String username, String message,
                       String timestamp, String messageType) {
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
    }

    // Getters
    public String getUserId()      { return userId; }
    public String getUsername()    { return username; }
    public String getMessage()     { return message; }
    public String getTimestamp()   { return timestamp; }
    public String getMessageType() { return messageType; }

    // Setters (required for Jackson deserialization)
    public void setUserId(String userId)           { this.userId = userId; }
    public void setUsername(String username)       { this.username = username; }
    public void setMessage(String message)         { this.message = message; }
    public void setTimestamp(String timestamp)     { this.timestamp = timestamp; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    @Override
    public String toString() {
        return "ChatMessage{userId='" + userId + "', username='" + username +
                "', messageType='" + messageType + "', timestamp='" + timestamp + "'}";
    }
}
