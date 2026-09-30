package com.flippingutilities.jobs;

import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class CacheUpdaterJobTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void snapshotsExistingFilesAndAnnouncesNewFilesOnce() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        Files.writeString(directory.resolve("existing.json"), "{}");
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);

        job.start();
        assertEquals(1L, executor.initialDelay);
        assertEquals(1L, executor.delay);
        assertEquals(TimeUnit.SECONDS, executor.unit);
        executor.tick();
        assertTrue(changes.isEmpty());

        Files.writeString(directory.resolve("new.json"), "{}");
        executor.tick();
        executor.tick();
        assertEquals(Arrays.asList("new.json"), changes);
        job.stop();
    }

    @Test
    public void detectsFullPrecisionModificationTimesAndAtomicReplacementSizeChanges() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        Path account = directory.resolve("account.json");
        FileTime initialTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 100_000));
        FileTime changedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 200_000));
        Files.writeString(account, "{}");
        Files.setLastModifiedTime(account, initialTime);
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.start();

        Files.setLastModifiedTime(account, changedTime);
        executor.tick();
        assertEquals(Arrays.asList("account.json"), changes);
        executor.tick();
        assertEquals(1, changes.size());

        Path replacement = directory.resolve("account.json.tmp");
        Files.writeString(replacement, "{\"changed\":true}");
        Files.setLastModifiedTime(replacement, changedTime);
        Files.move(replacement, account, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        executor.tick();
        executor.tick();
        assertEquals(Arrays.asList("account.json", "account.json"), changes);
        job.stop();
    }

    @Test
    public void announcesRecreationAfterAnAbsentPollWithoutAnnouncingRemoval() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        Path account = directory.resolve("account.json");
        Files.writeString(account, "{}");
        FileTime initialTime = Files.getLastModifiedTime(account);
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.start();

        Files.delete(account);
        executor.tick();
        assertTrue(changes.isEmpty());
        Files.writeString(account, "{}");
        Files.setLastModifiedTime(account, initialTime);
        executor.tick();
        assertEquals(Arrays.asList("account.json"), changes);
        job.stop();
    }

    @Test
    public void ignoresBackupsMetadataTemporaryFilesAndDirectories() throws Exception
    {
        Path directory = temporary.newFolder("accounts.json").toPath();
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.start();

        for (String name : Arrays.asList("account.backup.json", "account.special.json", "account.json.tmp",
            "account.json.pre-migration", ".temporary.json.tmp", "notes.txt"))
        {
            Files.writeString(directory.resolve(name), "{}");
        }
        Path nested = Files.createDirectory(directory.resolve("nested.json"));
        Files.writeString(nested.resolve("account.json"), "{}");
        executor.tick();
        assertTrue(changes.isEmpty());

        Files.writeString(directory.resolve("account.json"), "{}");
        executor.tick();
        assertEquals(Arrays.asList("account.json"), changes);
        job.stop();
    }

    @Test
    public void retriesMissingDirectoryWithoutDiscardingTheLastSuccessfulSnapshot() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        Files.writeString(directory.resolve("unchanged.json"), "{}");
        Files.writeString(directory.resolve("changed.json"), "{}");
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.start();

        Path displaced = directory.resolveSibling("displaced");
        Files.move(directory, displaced);
        executor.tick();
        executor.tick();
        executor.tick();
        assertTrue(changes.isEmpty());
        Files.writeString(displaced.resolve("changed.json"), "{\"changed\":true}");
        Files.move(displaced, directory);
        executor.tick();
        executor.tick();
        assertEquals(Arrays.asList("changed.json"), changes);
        job.stop();
    }

    @Test
    public void establishesInitialSnapshotAfterDirectoryAppears() throws Exception
    {
        Path directory = temporary.getRoot().toPath().resolve("accounts");
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.start();
        executor.tick();

        Files.createDirectory(directory);
        Files.writeString(directory.resolve("existing.json"), "{}");
        executor.tick();
        assertTrue(changes.isEmpty());
        Files.writeString(directory.resolve("new.json"), "{}");
        executor.tick();
        assertEquals(Arrays.asList("new.json"), changes);
        job.stop();
    }

    @Test
    public void startAndStopAreIdempotentAndStaleTasksCannotPollAfterRestart() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(changes::add);
        job.stop();
        job.start();
        job.start();
        assertEquals(1, executor.tasks.size());
        ManualTask original = executor.tasks.get(0);
        job.stop();
        job.stop();
        assertTrue(original.isCancelled());
        assertFalse(executor.isShutdown());
        Files.writeString(directory.resolve("while-stopped.json"), "{}");
        original.command.run();
        assertTrue(changes.isEmpty());

        job.start();
        assertEquals(2, executor.tasks.size());
        Files.writeString(directory.resolve("after-restart.json"), "{}");
        original.command.run();
        assertTrue(changes.isEmpty());
        executor.tick();
        assertEquals(Arrays.asList("after-restart.json"), changes);
        job.stop();
        assertTrue(executor.tasks.get(1).isCancelled());
        assertFalse(executor.isShutdown());
    }

    @Test
    public void failingSubscriberDoesNotStopOtherSubscribersOrLaterPolls() throws Exception
    {
        Path directory = temporary.newFolder("accounts").toPath();
        ManualExecutor executor = new ManualExecutor();
        CacheUpdaterJob job = new CacheUpdaterJob(Filepath.Unchecked.getRooted(directory), executor);
        List<String> changes = new ArrayList<>();
        job.subscribe(name -> { throw new IllegalStateException("Subscriber failed"); });
        job.subscribe(changes::add);
        job.start();

        Files.writeString(directory.resolve("first.json"), "{}");
        executor.tick();
        Files.writeString(directory.resolve("second.json"), "{}");
        executor.tick();
        assertEquals(Arrays.asList("first.json", "second.json"), changes);
        job.stop();
    }

    private static final class ManualExecutor extends ScheduledThreadPoolExecutor
    {
        private final List<ManualTask> tasks = new ArrayList<>();
        private long initialDelay;
        private long delay;
        private TimeUnit unit;

        private ManualExecutor()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
        {
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            ManualTask task = new ManualTask(command);
            tasks.add(task);
            return task;
        }

        private void tick()
        {
            ManualTask task = tasks.get(tasks.size() - 1);
            assertFalse(task.isCancelled());
            task.command.run();
        }
    }

    private static final class ManualTask extends FutureTask<Void> implements ScheduledFuture<Void>
    {
        private final Runnable command;

        private ManualTask(Runnable command)
        {
            super(command, null);
            this.command = command;
        }

        @Override
        public long getDelay(TimeUnit unit)
        {
            return 0;
        }

        @Override
        public int compareTo(Delayed other)
        {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
