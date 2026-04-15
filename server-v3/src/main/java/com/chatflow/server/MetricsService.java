package com.chatflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.*;
import java.time.Instant;

/**
 * Queries PostgreSQL and returns a JSON metrics snapshot.
 * Called by GET /metrics after a load test completes.
 */
public class MetricsService {

    private final String jdbcUrl;
    private final String dbUser = "chatflow";
    private final String dbPassword = "123456";
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricsService(String dbHost) {
        this.jdbcUrl = "jdbc:postgresql://" + dbHost + ":5432/chatdb";
        System.out.println("MetricsService configured: " + jdbcUrl);
    }

    /**
     * Runs all core and analytics queries and returns a single JSON string.
     */
    public String getMetrics() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            ObjectNode result = mapper.createObjectNode();
            result.put("queriedAt", Instant.now().toString());

            // ── Total messages ────────────────────────────────────────────────
            result.put("totalMessages", queryLong(conn,
                    "SELECT COUNT(*) FROM messages"));

            // ── Messages by type ──────────────────────────────────────────────
            ObjectNode byType = mapper.createObjectNode();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT message_type, COUNT(*) FROM messages GROUP BY message_type")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) byType.put(rs.getString(1), rs.getLong(2));
            }
            result.set("messagesByType", byType);

            // ── Core query 3: active users in last hour ───────────────────────
            result.put("activeUsersLastHour", queryLong(conn,
                    "SELECT COUNT(DISTINCT user_id) FROM messages " +
                    "WHERE timestamp >= NOW() - INTERVAL '1 hour'"));

            // ── Core query 4 example: rooms for the most active user ──────────
            String topUserId = queryString(conn,
                    "SELECT user_id FROM messages GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT 1");
            if (topUserId != null) {
                ObjectNode topUserRooms = mapper.createObjectNode();
                topUserRooms.put("userId", topUserId);
                ArrayNode rooms = mapper.createArrayNode();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT room_id, MAX(timestamp) as last_activity " +
                        "FROM messages WHERE user_id = ? " +
                        "GROUP BY room_id ORDER BY last_activity DESC")) {
                    ps.setString(1, topUserId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        ObjectNode r = mapper.createObjectNode();
                        r.put("roomId", rs.getString("room_id"));
                        r.put("lastActivity", rs.getTimestamp("last_activity").toInstant().toString());
                        rooms.add(r);
                    }
                }
                topUserRooms.set("rooms", rooms);
                result.set("topUserRooms", topUserRooms);
            }

            // ── Analytics: top 10 active users ───────────────────────────────
            ArrayNode topUsers = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, username, COUNT(*) as msg_count " +
                    "FROM messages GROUP BY user_id, username " +
                    "ORDER BY msg_count DESC LIMIT 10")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode u = mapper.createObjectNode();
                    u.put("userId", rs.getString("user_id"));
                    u.put("username", rs.getString("username"));
                    u.put("messageCount", rs.getLong("msg_count"));
                    topUsers.add(u);
                }
            }
            result.set("topUsers", topUsers);

            // ── Analytics: top 10 active rooms ───────────────────────────────
            ArrayNode topRooms = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT room_id, COUNT(*) as msg_count " +
                    "FROM messages GROUP BY room_id " +
                    "ORDER BY msg_count DESC LIMIT 10")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("roomId", rs.getString("room_id"));
                    r.put("messageCount", rs.getLong("msg_count"));
                    topRooms.add(r);
                }
            }
            result.set("topRooms", topRooms);

            // ── Analytics: messages per minute (last 30 min) ──────────────────
            ArrayNode perMinute = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DATE_TRUNC('minute', timestamp) as minute, COUNT(*) as count " +
                    "FROM messages WHERE timestamp >= NOW() - INTERVAL '30 minutes' " +
                    "GROUP BY minute ORDER BY minute")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode m = mapper.createObjectNode();
                    m.put("minute", rs.getTimestamp("minute").toInstant().toString());
                    m.put("count", rs.getLong("count"));
                    perMinute.add(m);
                }
            }
            result.set("messagesPerMinute", perMinute);

            // ── Core query 1 example: room 1 messages, last 5 min ────────────
            ArrayNode recentRoom1 = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT message_id, user_id, username, message, timestamp " +
                    "FROM messages WHERE room_id = '1' " +
                    "AND timestamp >= NOW() - INTERVAL '5 minutes' " +
                    "ORDER BY timestamp LIMIT 10")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode m = mapper.createObjectNode();
                    m.put("messageId", rs.getString("message_id"));
                    m.put("userId", rs.getString("user_id"));
                    m.put("username", rs.getString("username"));
                    m.put("message", rs.getString("message"));
                    m.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                    recentRoom1.add(m);
                }
            }
            result.set("room1RecentMessages", recentRoom1);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        }
    }

    private long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
