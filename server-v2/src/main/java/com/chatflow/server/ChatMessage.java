package com.chatflow.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * A domain class representing an incoming chat message to the server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {

    private UUID messageId;
    private String roomId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private MessageType messageType;
    private String serverId;
    private String clientIp;


    public ChatMessage() {
    }

    public ChatMessage(UUID messageId, String roomId, String userId, String username, String message, String timestamp,
                       MessageType messageType, String serverId, String clientIp) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.timestamp = timestamp;
        this.messageType = messageType;
        this.serverId = serverId;
        this.clientIp = clientIp;
    }

    // Getters
    public UUID getMessageId() {
        return messageId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public String getServerId() {
        return serverId;
    }

    public String getClientIp() {
        return clientIp;
    }

    // Setters (required for Jackson deserialization)
    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "messageId=" + messageId +
                ", roomId='" + roomId + '\'' +
                ", userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", message='" + message + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", messageType=" + messageType +
                ", serverId='" + serverId + '\'' +
                ", clientIp='" + clientIp + '\'' +
                '}';
    }
}

