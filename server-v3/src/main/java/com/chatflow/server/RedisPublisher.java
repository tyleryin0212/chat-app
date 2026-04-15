package com.chatflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.XAddParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RedisPublisher {

    private static final String CHANNEL_PREFIX = "room:";
    private static final String STREAM_PREFIX = "room-stream:";
    private static final long STREAM_MAX_LEN = 100_000L;

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MS = 10_000;

    private final JedisPool jedisPool;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitOpen = false;
    private volatile long openedAt = 0;

    public RedisPublisher(String host, int port) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(100);
        config.setMaxIdle(20);
        config.setMinIdle(10);
        this.jedisPool = new JedisPool(config, host, port);
        System.out.println("RedisPublisher initialized — host=" + host + " port=" + port);
    }

    public void publish(ChatMessage msg) throws Exception {
        if (circuitOpen) {
            if (System.currentTimeMillis() - openedAt < COOLDOWN_MS) {
                throw new Exception("Circuit breaker open — Redis unavailable");
            }
            circuitOpen = false;
            System.out.println("Circuit breaker half-open — testing Redis connection");
        }

        String channel = CHANNEL_PREFIX + msg.getRoomId();
        String streamKey = STREAM_PREFIX + msg.getRoomId();
        String json = mapper.writeValueAsString(msg);

        try (Jedis jedis = jedisPool.getResource()) {
            // Fan-out: notify all subscribed servers via Pub/Sub
            jedis.publish(channel, json);

            // Persistence: append to stream for consumer to write to DB
            Map<String, String> fields = new HashMap<>();
            fields.put("data", json);
            jedis.xadd(streamKey, XAddParams.xAddParams().maxLen(STREAM_MAX_LEN).approximateTrimming(), fields);

            failureCount.set(0);
        } catch (Exception e) {
            int failures = failureCount.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD) {
                circuitOpen = true;
                openedAt = System.currentTimeMillis();
                System.out.println("Circuit breaker opened after " + failures + " Redis failures");
            }
            throw e;
        }
    }

    public void publishBatch(List<ChatMessage> batch) throws Exception {
        if (circuitOpen) {
            if (System.currentTimeMillis() - openedAt < COOLDOWN_MS) {
                throw new Exception("Circuit breaker open — Redis unavailable");
            }
            circuitOpen = false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipe = jedis.pipelined();
            for (ChatMessage msg : batch) {
                String json = mapper.writeValueAsString(msg);
                Map<String, String> fields = new HashMap<>();
                fields.put("data", json);
                pipe.publish(CHANNEL_PREFIX + msg.getRoomId(), json);
                pipe.xadd(STREAM_PREFIX + msg.getRoomId(),
                        XAddParams.xAddParams().maxLen(STREAM_MAX_LEN).approximateTrimming(), fields);
            }
            pipe.sync();
            failureCount.set(0);
        } catch (Exception e) {
            int failures = failureCount.incrementAndGet();
            if (failures >= FAILURE_THRESHOLD) {
                circuitOpen = true;
                openedAt = System.currentTimeMillis();
                System.out.println("Circuit breaker opened after " + failures + " Redis failures");
            }
            throw e;
        }
    }

    public void close() {
        jedisPool.close();
    }
}
