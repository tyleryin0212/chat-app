package com.chatflow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.params.XReadGroupParams;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class RedisStreamConsumer implements Runnable {

    private static final String GROUP_NAME   = "db-writers";
    private static final String STREAM_PREFIX = "room-stream:";
    private static final int    DEDUP_CACHE_SIZE = 1000;
    private static final int    BATCH_SIZE   = 100;
    private static final int    BLOCK_MS     = 1000; // block up to 1s waiting for new entries

    private final String roomId;
    private final String streamKey;
    private final String consumerName;
    private final JedisPool jedisPool;
    private final BlockingQueue<ChatMessage> writeBuffer;
    private final ObjectMapper mapper = new ObjectMapper();

    // LRU-bounded dedup set — same pattern as RoomConsumer
    private final Set<String> seenMessageIds = Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>() {
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > DEDUP_CACHE_SIZE;
            }
        }
    );

    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong duplicatesSkipped = new AtomicLong(0);
    private final AtomicLong errors            = new AtomicLong(0);

    public RedisStreamConsumer(String roomId, JedisPool jedisPool,
                               BlockingQueue<ChatMessage> writeBuffer, String consumerName) {
        this.roomId       = roomId;
        this.streamKey    = STREAM_PREFIX + roomId;
        this.consumerName = consumerName;
        this.jedisPool    = jedisPool;
        this.writeBuffer  = writeBuffer;
    }

    @Override
    public void run() {
        // Outer loop: reconnects if the Jedis connection drops or stream/group is missing
        while (!Thread.currentThread().isInterrupted()) {
            ensureGroupExists(); // recreates group if stream was deleted
            try (Jedis jedis = jedisPool.getResource()) {
                System.out.println("RedisStreamConsumer ready | room=" + roomId + " | consumer=" + consumerName);

                while (!Thread.currentThread().isInterrupted()) {
                    // XREADGROUP GROUP db-writers <consumerName> COUNT 100 BLOCK 1000 STREAMS room-stream:<roomId> >
                    List<Map.Entry<String, List<StreamEntry>>> results = jedis.xreadGroup(
                        GROUP_NAME, consumerName,
                        XReadGroupParams.xReadGroupParams().count(BATCH_SIZE).block(BLOCK_MS),
                        Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
                    );

                    if (results == null || results.isEmpty()) continue;

                    for (Map.Entry<String, List<StreamEntry>> streamResult : results) {
                        for (StreamEntry entry : streamResult.getValue()) {
                            processEntry(jedis, entry);
                        }
                    }
                }

            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("RedisStreamConsumer disconnected | room=" + roomId + " | " + e.getMessage() + " — reconnecting in 2s");
                    try { Thread.sleep(2_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private void processEntry(Jedis jedis, StreamEntry entry) {
        String json = entry.getFields().get("data");
        try {
            String messageId = mapper.readTree(json).path("messageId").asText();

            // dedup check
            if (!messageId.isEmpty() && !seenMessageIds.add(messageId)) {
                jedis.xack(streamKey, GROUP_NAME, entry.getID());
                duplicatesSkipped.incrementAndGet();
                return;
            }

            ChatMessage msg = mapper.readValue(json, ChatMessage.class);

            if (writeBuffer.offer(msg)) {
                // Safe to ack — message is now in the write buffer headed to DB
                jedis.xack(streamKey, GROUP_NAME, entry.getID());
                messagesProcessed.incrementAndGet();
            } else {
                // Buffer full — do NOT ack. Entry stays pending in the stream and
                // will be reclaimed on restart via XPENDING, replacing the old DLQ.
                System.out.println("[WARN] Write buffer full — not acking entry " + entry.getID() + " room=" + roomId);
            }

        } catch (Exception e) {
            errors.incrementAndGet();
            System.out.println("[ERROR] Failed to process stream entry | room=" + roomId + " | id=" + entry.getID() + " | " + e.getMessage());
            // Do not ack — entry stays pending for recovery
        }
    }

    private void ensureGroupExists() {
        try (Jedis jedis = jedisPool.getResource()) {
            // makeStream=true creates the stream key if it doesn't exist yet
            jedis.xgroupCreate(streamKey, GROUP_NAME, new StreamEntryID(0, 0), true);
            System.out.println("Consumer group '" + GROUP_NAME + "' created for stream " + streamKey);
        } catch (Exception e) {
            // BUSYGROUP error means the group already exists — safe to ignore
            System.out.println("Consumer group '" + GROUP_NAME + "' already exists for " + streamKey);
        }
    }
}