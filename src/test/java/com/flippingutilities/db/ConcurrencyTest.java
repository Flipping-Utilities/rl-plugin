package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static com.flippingutilities.db.StorageTestOffers.complete;

/**
 * Verifies that SqliteStorage handles concurrent access safely (all public methods are
 * synchronized; this test exercises that contract under contention).
 */
public class ConcurrencyTest {

    private Path tempDir;
    private File dbFile;
    private SqliteStorage storage;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("concurrency_test_");
        dbFile = new File(tempDir.toFile(), "concurrent.db");
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();
        storage.upsertAccount("ConcurrentAcct", null);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testConcurrentInsertsAndQueries() throws Exception {
        final int numThreads = 4;
        final int insertsPerThread = 25;
        final String account = "ConcurrentAcct";
        final AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < insertsPerThread; i++) {
                        String uuid = "conc-" + threadId + "-" + i;
                        long ts = Instant.now().toEpochMilli() + (threadId * 1000L + i);
                        storage.recordTrade(account, complete(account, 4151, uuid, ts, 1, 50000, i % 2 == 0));
                        // Interleave a query every few inserts
                        if (i % 5 == 0) {
                            storage.loadAccount(account);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue("All threads should complete within 30s",
            latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals("No errors during concurrent inserts/queries", 0, errors.get());

        // Verify the total number of trades: each thread inserted insertsPerThread unique uuids.
        AccountData data = storage.loadAccount(account);
        assertEquals("Total trades should match sum of all inserts",
            numThreads * insertsPerThread,
            data.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
    }

    @Test
    public void testConcurrentTradeWritesAndAccountLoads() throws Exception {
        final int numThreads = 3;
        final String account = "ConcurrentAcct";
        final AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        // Thread 1 & 2: insert trades
        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 20; i++) {
                        storage.recordTrade(account, complete(account, 4151,
                            "agg-" + threadId + "-" + i,
                            Instant.now().toEpochMilli() + i,
                            1, 50000, true));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Thread 3: reload the account repeatedly
        pool.submit(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    storage.loadAccount(account);
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        assertTrue("All threads should complete within 30s",
            latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals("No errors during concurrent insert+query", 0, errors.get());
    }
}
