package com.chatflow.server;

import com.chatflow.consumer.RoomSessionManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public class RedisSubscriber {

    private static final String CHANNEL_PREFIX = "room:";
    private static final int TOTAL_ROOMS = 20;
    private static final long RECONNECT_DELAY_MS = 2_000;

    private final String host;
    private final int port;
    private final RoomSessionManager sessionManager;

    private volatile boolean running = false;
    private JedisPubSub pubSub;
    private Thread subscriberThread;

    public RedisSubscriber(String host, int port, RoomSessionManager sessionManager) {
        this.host = host;
        this.port = port;
        this.sessionManager = sessionManager;
    }

    public void start() {
        running = true;

        String[] channels = new String[TOTAL_ROOMS];
        for (int i = 1; i <= TOTAL_ROOMS; i++) {
            channels[i - 1] = CHANNEL_PREFIX + i;
        }

        subscriberThread = new Thread(() -> {
            while (running) {
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        // Strip "room:" prefix to get the roomId, then broadcast locally
                        String roomId = channel.substring(CHANNEL_PREFIX.length());
                        sessionManager.broadcast(roomId, message);
                    }
                };

                // Dedicated connection — Pub/Sub blocks indefinitely, never returned to a pool
                try (Jedis jedis = new Jedis(host, port)) {
                    System.out.println("RedisSubscriber connected — subscribing to " + TOTAL_ROOMS + " room channels");
                    jedis.subscribe(pubSub, channels); // blocks until unsubscribe() or disconnect
                } catch (Exception e) {
                    if (running) {
                        System.out.println("RedisSubscriber disconnected: " + e.getMessage() + " — reconnecting in " + RECONNECT_DELAY_MS + "ms");
                        try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
            }
        }, "redis-subscriber");

        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void stop() {
        running = false;
        if (pubSub != null && pubSub.isSubscribed()) {
            pubSub.unsubscribe();
        }
    }
}