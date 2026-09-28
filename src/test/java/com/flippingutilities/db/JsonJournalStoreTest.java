package com.flippingutilities.db;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class JsonJournalStoreTest {
    private static final String FIRST = "accounts/first/account";
    private static final String SECOND = "accounts/second/account";
    private final Gson gson = new Gson();

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void commitsOnlyNewRecordsAndReopensExactHistory() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "Player 日本語");
        for (int i = 0; i < 2000; i++) {
            initial.put("accounts/first/offers/" + i, new JsonPrimitive("old-history-" + i));
        }
        store.initialize(initial);
        byte[] checkpoint = Files.readAllBytes(directory.resolve("checkpoint.json"));
        Map<String, JsonElement> desired = copy(initial);
        desired.put("accounts/first/offers/new", new JsonPrimitive(9_007_199_254_740_993L));

        assertEquals(desired, store.commit(initial, desired));

        String journal = Files.readString(directory.resolve("journal.jsonl"));
        assertTrue(journal.contains("accounts/first/offers/new"));
        assertFalse("Saving a new trade must not rewrite old trades", journal.contains("old-history"));
        assertTrue("One offer should require a small transaction", journal.getBytes(StandardCharsets.UTF_8).length < 1024);
        assertArrayEquals(checkpoint, Files.readAllBytes(directory.resolve("checkpoint.json")));
        Map<String, JsonElement> reopened = store(directory).load();
        assertEquals(desired, reopened);
        assertEquals(9_007_199_254_740_993L, reopened.get("accounts/first/offers/new").getAsLong());
        long beforeRetry = Files.size(directory.resolve("journal.jsonl"));
        assertEquals(desired, store.commit(initial, desired));
        assertEquals("Retrying an acknowledged update must be idempotent", beforeRetry,
            Files.size(directory.resolve("journal.jsonl")));
    }

    @Test
    public void competingInitializationNeverReplacesAnExistingHistory() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = records(FIRST, "original");
        store(directory).initialize(initial);
        store(directory).initialize(records(FIRST, "stale migration"));
        assertEquals(initial, store(directory).load());
    }

    @Test
    public void explicitNullFieldsSurviveBothCheckpointsAndJournalTransactions() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        JsonObject metadata = new JsonObject();
        metadata.add("nullable", JsonNull.INSTANCE);
        Map<String, JsonElement> initial = new LinkedHashMap<>();
        initial.put(FIRST, metadata);
        store.initialize(initial);
        assertEquals(initial, store(directory).load());
        Map<String, JsonElement> desired = copy(initial);
        JsonObject offer = new JsonObject();
        offer.addProperty("quantity", 1);
        offer.add("tradeStartedAt", JsonNull.INSTANCE);
        desired.put("accounts/first/offers/new", offer);
        store.commit(initial, desired);

        assertEquals("Null fields are part of the storage schema, not absent properties", desired,
            store(directory).load());
    }

    @Test
    public void differentAccountsMergeWithoutRevertingTheOtherClientsChanges() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = twoAccounts();
        JsonJournalStore first = store(directory);
        first.initialize(initial);
        JsonJournalStore second = store(directory);
        Map<String, JsonElement> secondBase = second.load();
        Map<String, JsonElement> firstEdit = changed(initial, FIRST, "first edit");
        first.commit(initial, firstEdit);

        Map<String, JsonElement> result = second.commit(secondBase, changed(secondBase, SECOND, "second edit"));

        assertEquals(new JsonPrimitive("first edit"), result.get(FIRST));
        assertEquals(new JsonPrimitive("second edit"), result.get(SECOND));
        assertEquals(result, store(directory).load());
    }

    @Test
    public void sameAccountConflictRetainsBothTheDurableAndUnsavedHistory() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = records(FIRST, "account");
        initial.put("accounts/first/offers/original", new JsonPrimitive("original trade"));
        JsonJournalStore winner = store(directory);
        winner.initialize(initial);
        JsonJournalStore stale = store(directory);
        Map<String, JsonElement> base = stale.load();
        Map<String, JsonElement> winnerEdit = changed(initial, "accounts/first/offers/one", "first client trade");
        winner.commit(initial, winnerEdit);
        Map<String, JsonElement> staleEdit = changed(base, "accounts/first/offers/two", "second client trade");

        try {
            stale.commit(base, staleEdit);
            fail("Concurrent edits to one account require explicit recovery");
        } catch (JsonJournalStore.ConflictException expected) {
            assertTrue(expected.getMessage().contains("preserved"));
        }

        assertEquals(winnerEdit, store(directory).load());
        List<Path> recoveries;
        try (Stream<Path> files = Files.list(directory)) {
            recoveries = files.filter(path -> path.getFileName().toString().startsWith("conflict-"))
                .collect(Collectors.toList());
        }
        assertEquals(1, recoveries.size());
        JsonObject saved = new JsonParser().parse(Files.readString(recoveries.get(0))).getAsJsonObject();
        assertEquals(gson.toJsonTree(staleEdit), saved.get("records"));
    }

    @Test
    public void aDeletedAccountCannotBeResurrectedByAStaleWriter() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = twoAccounts();
        JsonJournalStore first = store(directory);
        first.initialize(initial);
        JsonJournalStore second = store(directory);
        Map<String, JsonElement> stale = second.load();
        Map<String, JsonElement> deleted = copy(initial);
        deleted.remove(FIRST);
        first.commit(initial, deleted);

        expectIOException(() -> second.commit(stale, changed(stale, FIRST, "stale session")));

        assertEquals(deleted, store(directory).load());
    }

    @Test
    public void concurrentInstancesSerializeWritesWithoutLostAccounts() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = twoAccounts();
        store(directory).initialize(initial);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String account : new String[]{FIRST, SECOND}) {
                futures.add(workers.submit(() -> {
                    try {
                        JsonJournalStore client = store(directory);
                        Map<String, JsonElement> expected = client.load();
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        for (int value = 1; value <= 25; value++) {
                            Map<String, JsonElement> desired = copy(expected);
                            desired.put(account, new JsonPrimitive(value));
                            expected = client.commit(expected, desired);
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
        Map<String, JsonElement> restored = store(directory).load();
        assertEquals(new JsonPrimitive(25), restored.get(FIRST));
        assertEquals(new JsonPrimitive(25), restored.get(SECOND));
    }

    @Test
    public void accountsAndSharedSettingsCommitTogetherOrRemainAtThePreviousRevision() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = twoAccounts();
        initial.put("accountwide", new JsonPrimitive("old settings"));
        JsonJournalStore store = store(directory);
        store.initialize(initial);
        Map<String, JsonElement> desired = changed(initial, FIRST, "first updated");
        desired.put(SECOND, new JsonPrimitive("second updated"));
        desired.put("accountwide", new JsonPrimitive("new settings"));
        store.commit(initial, desired);
        assertEquals(desired, store(directory).load());
        Path journal = directory.resolve("journal.jsonl");
        byte[] completed = Files.readAllBytes(journal);
        byte[] interrupted = java.util.Arrays.copyOf(completed, completed.length / 2);
        Files.write(journal, interrupted);

        assertEquals("An interrupted multi-account transaction cannot publish a partial update",
            initial, store(directory).load());
        assertEquals(0, Files.size(journal));
    }

    @Test
    public void interruptedTailKeepsTheCommittedPrefixAndAllowsRetry() throws Exception {
        Path directory = directory();
        Map<String, JsonElement> initial = records(FIRST, "initial");
        JsonJournalStore store = store(directory);
        store.initialize(initial);
        Map<String, JsonElement> committed = changed(initial, FIRST, "committed 日本語");
        store.commit(initial, committed);
        Path journal = directory.resolve("journal.jsonl");
        byte[] prefix = Files.readAllBytes(journal);
        Files.writeString(journal, "{\"formatVersion\":2,\"put\":{\"unfinished\":", StandardOpenOption.APPEND);

        JsonJournalStore restarted = store(directory);
        assertEquals(committed, restarted.load());
        assertArrayEquals(prefix, Files.readAllBytes(journal));
        Map<String, JsonElement> next = changed(committed, FIRST, "after retry");
        restarted.commit(committed, next);
        assertEquals(next, store(directory).load());
    }

    @Test
    public void corruptPrimaryRecoversTheBackupAndEveryCommittedTransaction() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = twoAccounts();
        store.initialize(initial);
        Map<String, JsonElement> current = changed(initial, FIRST, "latest");
        store.commit(initial, current);
        Files.writeString(directory.resolve("checkpoint.json"), "{broken");

        assertEquals(current, store(directory).load());
        assertEquals("{broken", Files.readString(directory.resolve("checkpoint.json")));
    }

    @Test
    public void unknownStorageVersionNeverFallsBackToAnOlderCheckpoint() throws Exception {
        Path directory = directory();
        store(directory).initialize(twoAccounts());
        Path primary = directory.resolve("checkpoint.json");
        JsonObject future = new JsonParser().parse(Files.readString(primary)).getAsJsonObject();
        future.addProperty("formatVersion", 999);
        Files.writeString(primary, gson.toJson(future));
        byte[] original = Files.readAllBytes(primary);

        expectIOException(() -> store(directory).load());
        expectIOException(() -> store(directory).initialize(Collections.emptyMap()));

        assertArrayEquals(original, Files.readAllBytes(primary));
    }

    @Test
    public void unreadableCheckpointsArePreservedAndCannotBeReinitialized() throws Exception {
        Path directory = directory();
        store(directory).initialize(twoAccounts());
        Files.writeString(directory.resolve("checkpoint.json"), "bad primary");
        Files.writeString(directory.resolve("checkpoint.previous.json"), "bad backup");
        byte[] journal = Files.readAllBytes(directory.resolve("journal.jsonl"));

        expectIOException(() -> store(directory).load());
        expectIOException(() -> store(directory).initialize(Collections.emptyMap()));

        assertEquals("bad primary", Files.readString(directory.resolve("checkpoint.json")));
        assertEquals("bad backup", Files.readString(directory.resolve("checkpoint.previous.json")));
        assertArrayEquals(journal, Files.readAllBytes(directory.resolve("journal.jsonl")));
    }

    @Test
    public void corruptCommittedTransactionFailsWithoutSkippingOrOverwritingIt() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "initial");
        store.initialize(initial);
        Map<String, JsonElement> first = changed(initial, FIRST, "first committed");
        store.commit(initial, first);
        store.commit(first, changed(first, FIRST, "second committed"));
        Path journal = directory.resolve("journal.jsonl");
        String corrupted = Files.readString(journal).replace("first committed", "first corrupted");
        Files.writeString(journal, corrupted);

        expectIOException(() -> store(directory).load());
        expectIOException(() -> store(directory).commit(initial, changed(initial, FIRST, "new value")));

        assertEquals(corrupted, Files.readString(journal));
    }

    @Test
    public void checkpointRotationRetainsEnoughJournalToRecoverThePreviousCheckpoint() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "initial");
        store.initialize(initial);
        Map<String, JsonElement> large = changed(initial, "accounts/first/offers/large",
            "x".repeat(8 * 1024 * 1024 + 1024));
        store.commit(initial, large);
        Map<String, JsonElement> latest = changed(large, FIRST, "latest account metadata");
        store.commit(large, latest);
        assertTrue("Old journal transactions should be checkpointed", Files.size(directory.resolve("journal.jsonl")) < 2048);
        Files.writeString(directory.resolve("checkpoint.json"), "{interrupted checkpoint");

        assertEquals(latest, store(directory).load());
    }

    @Test
    public void unavailableJournalDoesNotAcknowledgeAWriteAndCanBeRetried() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "original");
        store.initialize(initial);
        Map<String, JsonElement> desired = changed(initial, FIRST, "retry this update");
        Path journal = directory.resolve("journal.jsonl");
        Path held = directory.resolve("journal.saved");
        Files.move(journal, held);
        Files.createDirectory(journal);

        expectIOException(() -> store.commit(initial, desired));

        assertEquals(0, Files.size(held));
        Files.delete(journal);
        Files.move(held, journal);
        assertEquals(initial, store(directory).load());
        store.commit(initial, desired);
        assertEquals(desired, store(directory).load());
    }

    @Test
    public void missingCheckpointsCannotReplaceAnExistingJournalWithEmptyData() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "original");
        store.initialize(initial);
        store.commit(initial, changed(initial, FIRST, "latest"));
        byte[] journal = Files.readAllBytes(directory.resolve("journal.jsonl"));
        Files.delete(directory.resolve("checkpoint.json"));
        Files.delete(directory.resolve("checkpoint.previous.json"));

        expectIOException(() -> store(directory).initialize(Collections.emptyMap()));

        assertArrayEquals(journal, Files.readAllBytes(directory.resolve("journal.jsonl")));
        assertFalse(Files.exists(directory.resolve("checkpoint.json")));
    }

    private Path directory() throws IOException {
        return folder.newFolder().toPath();
    }

    @Test
    public void injectedPrettyPrintingStillProducesOnePhysicalLinePerTransaction() throws Exception {
        Path directory = directory();
        JsonJournalStore store = new JsonJournalStore(directory,
            new com.google.gson.GsonBuilder().setPrettyPrinting().create());
        Map<String, JsonElement> initial = records(FIRST, "original");
        store.initialize(initial);
        Map<String, JsonElement> desired = changed(initial, FIRST, "日本語\nsecond line");
        store.commit(initial, desired);
        try (Stream<String> lines = Files.lines(directory.resolve("journal.jsonl"))) {
            assertEquals(1, lines.count());
        }
        assertEquals(desired, new JsonJournalStore(directory, new Gson()).load());
    }

    @Test
    public void interruptedInitializationCanResumeBeforeAnyCheckpointWasPublished() throws Exception {
        Path directory = directory();
        Files.write(directory.resolve("initializing"), new byte[0]);
        Files.write(directory.resolve("journal.jsonl"), new byte[0]);
        Map<String, JsonElement> initial = records(FIRST, "legacy import");
        store(directory).initialize(initial);
        assertEquals(initial, store(directory).load());
        assertFalse(Files.exists(directory.resolve("initializing")));
    }

    @Test
    public void missingJournalNeverSilentlyDiscardsCommittedChanges() throws Exception {
        Path directory = directory();
        JsonJournalStore store = store(directory);
        Map<String, JsonElement> initial = records(FIRST, "original");
        store.initialize(initial);
        store.commit(initial, changed(initial, FIRST, "durable change"));
        Files.delete(directory.resolve("journal.jsonl"));
        expectIOException(() -> store(directory).load());
        assertFalse(Files.exists(directory.resolve("journal.jsonl")));
    }

    private JsonJournalStore store(Path directory) {
        return new JsonJournalStore(directory, gson);
    }

    private Map<String, JsonElement> twoAccounts() {
        Map<String, JsonElement> records = records(FIRST, "first original");
        records.put(SECOND, new JsonPrimitive("second original"));
        return records;
    }

    private Map<String, JsonElement> records(String key, String value) {
        Map<String, JsonElement> records = new LinkedHashMap<>();
        records.put(key, new JsonPrimitive(value));
        return records;
    }

    private Map<String, JsonElement> changed(Map<String, JsonElement> source, String key, String value) {
        Map<String, JsonElement> result = copy(source);
        result.put(key, new JsonPrimitive(value));
        return result;
    }

    private Map<String, JsonElement> copy(Map<String, JsonElement> records) {
        return new LinkedHashMap<>(records);
    }

    private void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            fail("Expected the operation to reject unsafe persistence");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
