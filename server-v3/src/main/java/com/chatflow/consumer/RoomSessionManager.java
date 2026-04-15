package com.chatflow.consumer;

import org.java_websocket.WebSocket;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomSessionManager {

    private final ConcurrentHashMap<String, Set<WebSocket>> roomSessions = new ConcurrentHashMap<>();

    public void addSession(String roomId, WebSocket ws) {
        roomSessions.computeIfAbsent(roomId, k ->
                Collections.newSetFromMap(new ConcurrentHashMap<>())
        ).add(ws);
    }

    public void removeSession(String roomId, WebSocket ws) {
        Set<WebSocket> sessions = roomSessions.get(roomId);
        if (sessions != null) sessions.remove(ws);
    }

    public void broadcast(String roomId, String message) {
        Set<WebSocket> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) return;
        for (WebSocket ws : sessions) {
            if (ws.isOpen()) {
                try {
                    ws.send(message);
                } catch (Exception e) {
                    System.out.println("Failed to send to client in room " + roomId + ": " + e.getMessage());
                }
            }
        }
    }

    public int getTotalSessions() {
        return roomSessions.values().stream().mapToInt(Set::size).sum();
    }
}
