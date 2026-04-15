package com.chatflow.client;

public class ChatMessage {

    private String messageId;
    private String userId;
    private String username;
    private String message;
    private String roomId;
    private String timestamp;
    private String messageType;

    public String getMessageId()   { return messageId; }
    public String getUserId()      { return userId; }
    public String getUsername()    { return username; }
    public String getMessage()     { return message; }
    public String getRoomId()      { return roomId; }
    public String getTimestamp()   { return timestamp; }
    public String getMessageType() { return messageType; }

    public void setMessageId(String messageId)     { this.messageId = messageId; }
    public void setUserId(String userId)           { this.userId = userId; }
    public void setUsername(String username)       { this.username = username; }
    public void setMessage(String message)         { this.message = message; }
    public void setRoomId(String roomId)           { this.roomId = roomId; }
    public void setTimestamp(String timestamp)     { this.timestamp = timestamp; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
}