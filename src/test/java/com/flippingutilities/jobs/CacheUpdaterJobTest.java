package com.flippingutilities.jobs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class CacheUpdaterJobTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void checksJournalGrowthEvenWhenTimestampsAreEqual() throws Exception {
        Path directory = folder.getRoot().toPath();
        Path journal = directory.resolve("journal.jsonl");
        Files.writeString(journal, "first");
        FileTime time = Files.getLastModifiedTime(journal);
        CacheUpdaterJob job = new CacheUpdaterJob(directory);
        AtomicInteger calls = new AtomicInteger();
        job.subscribe(ignored -> calls.incrementAndGet());
        try {
            job.updateCacheRealTime();
            job.updateCacheRealTime();
            assertEquals(1, calls.get());
            Files.writeString(journal, "first\nsecond");
            Files.setLastModifiedTime(journal, time);
            job.updateCacheRealTime();
            assertEquals(2, calls.get());
        } finally {
            job.stop();
        }
    }

    @Test
    public void observesCheckpointReplacementWithoutWalkingLegacyAccountFiles() throws Exception {
        Path directory = folder.getRoot().toPath();
        Path checkpoint = directory.resolve("checkpoint.json");
        Files.writeString(checkpoint, "old");
        CacheUpdaterJob job = new CacheUpdaterJob(directory);
        AtomicInteger calls = new AtomicInteger();
        job.subscribe(ignored -> calls.incrementAndGet());
        try {
            job.updateCacheRealTime();
            Files.writeString(directory.resolve("Unrelated.json"), "ignored");
            job.updateCacheRealTime();
            assertEquals(1, calls.get());
            Path replacement = directory.resolve("new.tmp");
            Files.writeString(replacement, "new checkpoint");
            Files.move(replacement, checkpoint, StandardCopyOption.REPLACE_EXISTING);
            job.updateCacheRealTime();
            assertEquals(2, calls.get());
        } finally {
            job.stop();
        }
    }
}
