package com.chatflow.client;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Client end point for sending messages concurrently. Warm up phase deleted from first assignment,
 * for more accurate testing results. Thread counts to be tested are 64, 128, 256, 512.
 * Setting Main Threads as 20 as a default constant, but can be changed with input argument.
 */
public class LoadTestClient {

    // ---- Configuration ----
    private static final String SERVER_BASE_URL  = "ws://chatflow-alb-14549874.us-west-2.elb.amazonaws.com/chat/";
    private static final int    TOTAL_MESSAGES   = 512000;
    private static final int    QUEUE_CAPACITY   = 10_000;
    private static final int    MAIN_THREADS     = 20;


    public static void main(String[] args) throws InterruptedException {
        int mainThreads = args.length > 0 ? Integer.parseInt(args[0]) : MAIN_THREADS;
        System.out.println("Starting LoadTestClient with " + mainThreads + " threads.");

        BlockingQueue<List<String>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        MetricsCollector metrics = new MetricsCollector();

        // Start message generator — produces all 500K messages into the queue
        Thread generatorThread = new Thread(
                new MessageGenerator(queue, TOTAL_MESSAGES), "msg-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();

        metrics.startTimer();

        // Start sender threads
        CountDownLatch mainLatch = new CountDownLatch(mainThreads);
        int msgsPerThread = TOTAL_MESSAGES / mainThreads;
        int remainder     = TOTAL_MESSAGES % mainThreads;  // handle uneven division

        for (int i = 0; i < mainThreads; i++) {
            // Give the remainder messages to the last thread
            int threadMsgs = (i == mainThreads - 1) ? msgsPerThread + remainder : msgsPerThread;
            Thread t = new Thread(
                    new SenderThread(queue, threadMsgs, SERVER_BASE_URL, metrics, mainLatch),
                    "sender-" + i);
            t.start();
        }

        mainLatch.await();
        metrics.stopTimer();
        metrics.printSummary();
        System.out.println("Total client threads: " + mainThreads);
        System.out.println("=======================================");
    }
}
