package com.chatflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.sql.*;
import java.time.Instant;

/**
 * Queries PostgreSQL and returns a JSON metrics snapshot.
 * Uses HikariCP for DB connection pooling and Redis for shared caching.
 *
 * Cache strategy:
 *   - GET metrics:cache from Redis → return immediately if hit (~1ms)
 *   - On miss: run DB queries, SETEX metrics:cache 10 <json>
 *   - Both servers share the same cache — DB queried at most once per 10s total
 */
public class MetricsService {

    private static final int    DB_POOL_SIZE  = 5;
    private static final String CACHE_KEY     = "metrics:cache";
    private static final int    CACHE_TTL_SEC = 10;

    private final HikariDataSource dataSource;
    private final JedisPool        jedisPool;
    private final ObjectMapper     mapper = new ObjectMapper();

    public MetricsService(String dbHost, String redisHost) {
        // ── PostgreSQL connection pool ─────────────────────────────────────────
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + dbHost + ":5432/chatdb");
        config.setUsername("chatflow");
        config.setPassword("123456");
        config.setMaximumPoolSize(DB_POOL_SIZE);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "25");
        this.dataSource = new HikariDataSource(config);

        // ── Redis cache pool (small — just for cache GET/SET) ─────────────────
        JedisPoolConfig jedisConfig = new JedisPoolConfig();
        jedisConfig.setMaxTotal(5);
        jedisConfig.setMaxIdle(2);
        jedisConfig.setMinIdle(1);
        this.jedisPool = new JedisPool(jedisConfig, redisHost, 6379);

        System.out.println("MetricsService initialized — db=" + dbHost + " redis=" + redisHost);
    }

    /**
     * Returns cached JSON from Redis if available, otherwise queries PostgreSQL,
     * stores the result in Redis with a 10s TTL, and returns it.
     */
    public String getMetrics() throws Exception {
        // ── Cache check ───────────────────────────────────────────────────────
        try (Jedis jedis = jedisPool.getResource()) {
            String cached = jedis.get(CACHE_KEY);
            if (cached != null) {
                return cached; // cache hit — no DB call
            }
        } catch (Exception e) {
            System.out.println("Redis cache read failed, falling back to DB: " + e.getMessage());
        }

        // ── Cache miss — query DB ─────────────────────────────────────────────
        String fresh = runAllQueries();

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(CACHE_KEY, CACHE_TTL_SEC, fresh);
        } catch (Exception e) {
            System.out.println("Redis cache write failed: " + e.getMessage());
        }

        return fresh;
    }

    private String runAllQueries() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
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

            // ── Active users in last hour ─────────────────────────────────────
            result.put("activeUsersLastHour", queryLong(conn,
                    "SELECT COUNT(DISTINCT user_id) FROM messages " +
                    "WHERE timestamp >= NOW() - INTERVAL '1 hour'"));

            // ── Top user and their rooms ──────────────────────────────────────
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

            // ── Top 10 active users ───────────────────────────────────────────
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

            // ── Top 10 active rooms ───────────────────────────────────────────
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

            // ── Messages per minute (last 30 min) ─────────────────────────────
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

            // ── Room 1 recent messages (last 5 min) ───────────────────────────
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

    public void close() {
        dataSource.close();
        jedisPool.close();
    }
}