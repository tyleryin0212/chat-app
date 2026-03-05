package com.chatflow.consumer;

import org.java_websocket.WebSocket;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomSessionManager {
    // map of roomId -> set of connected WebSocket clients
    private final ConcurrentHashMap<String, Set<WebSocket>> roomSessions
            = new ConcurrentHashMap<>();

    // ---- session lifecycle ----

    public void addSession(String roomId, WebSocket ws) {
        //creates the set if roomId is new
        roomSessions.computeIfAbsent(roomId, k ->
                Collections.newSetFromMap(new ConcurrentHashMap<>())
        ).add(ws);
        System.out.println("Client added to room " + roomId
                + ", room size = " + roomSessions.get(roomId).size());
    }

    public void removeSession(String roomId, WebSocket ws) {
        Set<WebSocket> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(ws);
            System.out.println("Client removed from room " + roomId
            + ", room size = " + sessions.size());
        }
    }

    // ---- broadcast ----

    public void broadcast(String roomId, String message) {
        Set<WebSocket> sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocket ws : sessions) {
            if (ws.isOpen()) {
                ws.send(message);
            }
        }

    }

    // stats
    public int getSessionCount(String roomId) {
        Set<WebSocket> sessions = roomSessions.get(roomId);
        return sessions == null ? 0 : sessions.size();
    }

    public int getTotalSessions() {
        return roomSessions.values().stream().mapToInt(Set::size).sum();
    }


}
