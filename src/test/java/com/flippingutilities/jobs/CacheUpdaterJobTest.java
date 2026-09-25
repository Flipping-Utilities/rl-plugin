package com.flippingutilities.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class CacheUpdaterJobTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stoppingBeforeStartIsSafeAndTerminal() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CacheUpdaterJob job = new CacheUpdaterJob(directory(), executor);
        job.stop();
        job.stop();
        job.start();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, executor.getTaskCount());
    }

    @Test
    public void repeatedStartSchedulesOneWatcherAndStopCancelsPendingStart() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        CacheUpdaterJob job = new CacheUpdaterJob(directory(), executor);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try
            {
                releaseWorker.await();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        try
        {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            job.start();
            job.start();
            assertEquals(1, executor.getQueue().size());
        }
        finally
        {
            job.stop();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void subscriberFailureDoesNotStopNotificationsAndRunningWatcherTerminates() throws Exception
    {
        Path directory = directory();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(directory, executor);
        CountDownLatch received = new CountDownLatch(2);
        AtomicInteger files = new AtomicInteger();
        AtomicBoolean firstNotification = new AtomicBoolean(true);
        job.subscribe(file -> {
            if (firstNotification.getAndSet(false))
            {
                throw new IllegalStateException("subscriber failed");
            }
        });
        job.subscribe(file -> received.countDown());
        try
        {
            job.start();
            // Use real filesystem notifications. Repeated unique writes accommodate the
            // OS watcher startup without relying on a sleep or fixed notification delay.
            // Java 11 on macOS polls directories about every 10 seconds.
            writer.scheduleAtFixedRate(() -> {
                try
                {
                    Files.createFile(directory.resolve("account-" + files.incrementAndGet() + ".json"));
                }
                catch (Exception e)
                {
                    throw new AssertionError(e);
                }
            }, 0, 50, TimeUnit.MILLISECONDS);
            assertTrue("watcher should keep notifying healthy subscribers", received.await(30, TimeUnit.SECONDS));
        }
        finally
        {
            writer.shutdownNow();
            job.stop();
            job.stop();
        }
        assertTrue("blocked watcher thread should exit", executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void missingDirectoryExhaustsRetriesAndReleasesWorker() throws Exception
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(directory().resolve("missing"), executor);
        try
        {
            job.start();
            assertTrue("failed watcher should release its executor", executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        finally
        {
            job.stop();
        }
    }

    private Path directory()
    {
        return temporaryFolder.getRoot().toPath();
    }
}
