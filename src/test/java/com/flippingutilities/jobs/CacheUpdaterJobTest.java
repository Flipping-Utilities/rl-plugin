package com.flippingutilities.jobs;

import net.runelite.client.util.Filepath;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class CacheUpdaterJobTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stoppingBeforeStartIsSafeAndTerminal()
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        try
        {
            CacheUpdaterJob job = new CacheUpdaterJob(directory(), executor);
            job.stop();
            job.stop();
            job.start();
            assertTrue("a stopped job must never schedule polls again", executor.getQueue().isEmpty());
            assertTrue(job.isStopped());
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    public void repeatedStartSchedulesOnePollerAndStopReleasesIt() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        try
        {
            CacheUpdaterJob job = new CacheUpdaterJob(directory(), executor);
            job.start();
            job.start();
            assertEquals("only one poll task may be scheduled", 1, executor.getQueue().size());
            job.stop();
            assertTrue("stop must release the poll task", executor.getQueue().isEmpty());
            job.start();
            assertTrue("stop is terminal", executor.getQueue().isEmpty());
            assertTrue(job.isStopped());
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    public void subscriberFailureDoesNotStopNotifications() throws Exception
    {
        Path directory = temporaryFolder.newFolder("accounts").toPath();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        CountDownLatch received = new CountDownLatch(2);
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
            // Files created after start returns are strictly after the baseline snapshot and
            // are picked up by the next poll; no OS watcher startup to wait for.
            Files.createFile(directory.resolve("account-1.json"));
            Files.createFile(directory.resolve("account-2.json"));
            assertTrue("poller should keep notifying healthy subscribers",
                received.await(10, TimeUnit.SECONDS));
        }
        finally
        {
            job.stop();
        }
        executor.shutdownNow();
    }

    @Test
    public void missingDirectoryExhaustsRetriesAndReleasesPoller() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        Path missing = temporaryFolder.getRoot().toPath().resolve("missing");
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(missing), executor);
        try
        {
            job.start();
            long deadline = System.currentTimeMillis() + 15_000;
            while (!job.isStopped() && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(100);
            }
            assertTrue("permanent read failure must stop the poller", job.isStopped());
            assertTrue("a stopped poller must release its task", executor.getQueue().isEmpty());
        }
        finally
        {
            job.stop();
            executor.shutdownNow();
        }
    }

    private Filepath directory()
    {
        return Filepath.Unchecked.getRooted(temporaryFolder.getRoot().toPath());
    }
}
