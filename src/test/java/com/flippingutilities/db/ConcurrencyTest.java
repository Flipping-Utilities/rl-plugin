package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static com.flippingutilities.db.StorageTestOffers.complete;

/**
 * Exercises contention between threads sharing a storage instance and independent clients.
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

    @Test
    public void independentClientsCanWriteDifferentAccountsWithoutSnapshotUpgradeFailures() throws Exception {
        SqliteStorage otherClient = new SqliteStorage(dbFile);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            otherClient.initializeSchema();
            otherClient.upsertAccount("OtherAcct", null);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = pool.submit(() -> {
                writeRepeatedly(storage, "ConcurrentAcct", ready, start);
                return null;
            });
            Future<?> second = pool.submit(() -> {
                writeRepeatedly(otherClient, "OtherAcct", ready, start);
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            // Future.get exposes any storage failure instead of merely counting completed threads.
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            for (String account : new String[]{"ConcurrentAcct", "OtherAcct"}) {
                AccountData loaded = storage.loadAccount(account);
                assertEquals(999L, loaded.getAccumulatedSessionTimeMillis());
                assertEquals(40, loaded.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
            }
            assertTrue(storage.getConnection().getAutoCommit());
            assertTrue(otherClient.getConnection().getAutoCommit());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            otherClient.close();
        }
    }

    @Test
    public void failedWriteReservationDiscardsTheDriversPartialTransactionState() throws Exception {
        SqliteStorage otherClient = new SqliteStorage(dbFile);
        Connection firstConnection = storage.getConnection();
        try (Statement settings = firstConnection.createStatement()) {
            settings.execute("PRAGMA busy_timeout=25");
        }
        try {
            Connection writer = otherClient.getConnection();
            writer.setAutoCommit(false);
            try {
                otherClient.upsertFavorite("OtherAcct", 4151, true, "held");
                try {
                    storage.updateAccountSessionTime("ConcurrentAcct", 123L);
                    fail("The reserved writer must outlast this client's short busy timeout");
                } catch (IllegalStateException failure) {
                    assertTrue(failure.getCause() instanceof SQLException);
                    assertTrue(failure.getCause().getMessage().contains("SQLITE_BUSY"));
                }
                assertTrue("A failed BEGIN must not leave JDBC claiming to own a transaction",
                    firstConnection.isClosed());
                assertNotNull("A subsequent read can reconnect while the other client still writes",
                    storage.loadAccount("ConcurrentAcct"));
                assertTrue(storage.getConnection().getAutoCommit());
                assertFalse(writer.getAutoCommit());
            } finally {
                writer.rollback();
                writer.setAutoCommit(true);
            }
            storage.updateAccountSessionTime("ConcurrentAcct", 456L);
            assertEquals(456L, storage.loadAccount("ConcurrentAcct").getAccumulatedSessionTimeMillis());
        } finally {
            otherClient.close();
        }
    }

    private void writeRepeatedly(SqliteStorage client, String account, CountDownLatch ready,
                                 CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        for (int i = 0; i < 1000; i++) {
            client.updateAccountSessionTime(account, i);
            if (i % 25 == 0) {
                client.recordTrade(account, complete(account, 4151, account + "-" + i,
                    1700000000000L + i, 1, 50000, true));
            }
        }
    }
}
