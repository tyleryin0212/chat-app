package com.chatflow.server;

import com.chatflow.consumer.RoomSessionManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;

public class RedisSubscriber {

    private static final String CHANNEL_PREFIX    = "room:";
    private static final int    TOTAL_ROOMS       = 20;
    private static final int    NUM_THREADS       = 4;   // each handles 5 rooms
    private static final long   RECONNECT_DELAY_MS = 2_000;

    private final String host;
    private final int port;
    private final RoomSessionManager sessionManager;

    private volatile boolean running = false;
    private final JedisPubSub[] pubSubs = new JedisPubSub[NUM_THREADS];
    private final List<Thread> subscriberThreads = new ArrayList<>(NUM_THREADS);

    public RedisSubscriber(String host, int port, RoomSessionManager sessionManager) {
        this.host           = host;
        this.port           = port;
        this.sessionManager = sessionManager;
    }

    public void start() {
        running = true;
        int roomsPerThread = TOTAL_ROOMS / NUM_THREADS;

        for (int t = 0; t < NUM_THREADS; t++) {
            int startRoom = t * roomsPerThread + 1;
            int endRoom   = (t == NUM_THREADS - 1) ? TOTAL_ROOMS : startRoom + roomsPerThread - 1;

            String[] channels = new String[endRoom - startRoom + 1];
            for (int i = startRoom; i <= endRoom; i++) {
                channels[i - startRoom] = CHANNEL_PREFIX + i;
            }

            final int idx = t;
            Thread thread = new Thread(() -> {
                while (running) {
                    JedisPubSub pubSub = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            String roomId = channel.substring(CHANNEL_PREFIX.length());
                            sessionManager.broadcast(roomId, message);
                        }
                    };
                    pubSubs[idx] = pubSub;

                    try (Jedis jedis = new Jedis(host, port)) {
                        System.out.println("RedisSubscriber-" + idx + " connected — rooms " + startRoom + "-" + endRoom);
                        jedis.subscribe(pubSub, channels); // blocks until disconnect
                    } catch (Exception e) {
                        if (running) {
                            System.out.println("RedisSubscriber-" + idx + " disconnected: " + e.getMessage()
                                    + " — reconnecting in " + RECONNECT_DELAY_MS + "ms");
                            try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                    }
                }
            }, "redis-subscriber-" + t);

            thread.setDaemon(true);
            subscriberThreads.add(thread);
            thread.start();
        }
    }

    public void stop() {
        running = false;
        for (JedisPubSub pubSub : pubSubs) {
            if (pubSub != null && pubSub.isSubscribed()) {
                try { pubSub.unsubscribe(); } catch (Exception ignored) {}
            }
        }
    }
}