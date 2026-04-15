package com.chatflow.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

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

    public ChatMessage() {}

    public UUID getMessageId()       { return messageId; }
    public String getRoomId()        { return roomId; }
    public String getUserId()        { return userId; }
    public String getUsername()      { return username; }
    public String getMessage()       { return message; }
    public String getTimestamp()     { return timestamp; }
    public MessageType getMessageType() { return messageType; }
    public String getServerId()      { return serverId; }
    public String getClientIp()      { return clientIp; }

    public void setMessageId(UUID messageId)          { this.messageId = messageId; }
    public void setRoomId(String roomId)              { this.roomId = roomId; }
    public void setUserId(String userId)              { this.userId = userId; }
    public void setUsername(String username)          { this.username = username; }
    public void setMessage(String message)            { this.message = message; }
    public void setTimestamp(String timestamp)        { this.timestamp = timestamp; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    public void setServerId(String serverId)          { this.serverId = serverId; }
    public void setClientIp(String clientIp)          { this.clientIp = clientIp; }
}
