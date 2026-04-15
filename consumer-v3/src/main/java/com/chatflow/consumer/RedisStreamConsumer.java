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

    // pipeline checkpoint 3: consumed from Redis stream
    private static final long LOG_INTERVAL_MS = 10_000;
    private long lastLogMs = System.currentTimeMillis();

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

                // Reclaim any entries delivered but not ACKed by a previous run of this consumer.
                // Must drain PEL (ID "0-0") before switching to new entries (">").
                reclaimPending(jedis);

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

                    long now = System.currentTimeMillis();
                    if (now - lastLogMs >= LOG_INTERVAL_MS) {
                        System.out.printf("[StreamConsumer room=%s] checkpoint-3: processed=%d duplicates=%d errors=%d%n",
                                roomId, messagesProcessed.get(), duplicatesSkipped.get(), errors.get());
                        lastLogMs = now;
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // restore flag — exits outer while loop
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.out.println("RedisStreamConsumer disconnected | room=" + roomId + " | " + e.getMessage() + " — reconnecting in 2s");
                    try { Thread.sleep(2_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    /**
     * Drains the consumer's PEL (pending entry list) by reading with ID "0-0".
     * Called once per connection, before switching to ">" (new entries only).
     * Handles entries that were delivered but not ACKed by a previous run.
     */
    private void reclaimPending(Jedis jedis) throws InterruptedException {
        int reclaimed = 0;
        while (!Thread.currentThread().isInterrupted()) {
            List<Map.Entry<String, List<StreamEntry>>> pending = jedis.xreadGroup(
                GROUP_NAME, consumerName,
                XReadGroupParams.xReadGroupParams().count(BATCH_SIZE),
                Map.of(streamKey, new StreamEntryID(0, 0))
            );
            // Jedis 5.x returns [{stream → []}] (not null/empty) when the PEL is empty.
            // Must count actual entries — not check the outer list.
            int entryCount = (pending == null) ? 0 :
                pending.stream().mapToInt(e -> e.getValue().size()).sum();
            if (entryCount == 0) break;

            for (Map.Entry<String, List<StreamEntry>> streamResult : pending) {
                for (StreamEntry entry : streamResult.getValue()) {
                    processEntry(jedis, entry);
                    reclaimed++;
                }
            }
        }
        if (reclaimed > 0) {
            System.out.printf("[StreamConsumer room=%s] reclaimed %d pending entries%n", roomId, reclaimed);
        }
    }

    private void processEntry(Jedis jedis, StreamEntry entry) throws InterruptedException {
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

            // Blocking put — if writeBuffer is full, this thread blocks until
            // a BatchWriter drains space. This propagates backpressure to Redis:
            // consumers stop reading the stream, which stays the backpressure buffer.
            // No messages are dropped or stuck in the PEL.
            writeBuffer.put(msg);
            jedis.xack(streamKey, GROUP_NAME, entry.getID());
            messagesProcessed.incrementAndGet();

        } catch (Exception e) {
            errors.incrementAndGet();
            System.out.println("[ERROR] Failed to process stream entry | room=" + roomId + " | id=" + entry.getID() + " | " + e.getMessage());
            // Do not ack — entry stays pending for recovery
        }
    }

    public long getMessagesProcessed() { return messagesProcessed.get(); }
    public long getDuplicatesSkipped() { return duplicatesSkipped.get(); }
    public long getErrors()            { return errors.get(); }

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