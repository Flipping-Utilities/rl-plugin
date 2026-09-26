package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SandboxTradePersisterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sqliteImportModelsSkipInitialJsonParseAndLaterReloadsStillReadSnapshots() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SandboxTradePersister persister = new SandboxTradePersister(countingGson(reads), temporary.getRoot());
        AccountData imported = new AccountData();
        persister.writeToFile("Alice", imported);
        Map<String, AccountData> importedAccounts = new HashMap<>();
        importedAccounts.put("Alice", imported);
        Map<String, AccountData> metadata = persister.preloadAccounts(importedAccounts);
        assertSame(imported, metadata.get("Alice"));
        importedAccounts.clear();
        metadata.clear();
        assertSame(imported, persister.loadAllAccounts().get("Alice"));
        assertEquals("SQLite DAO models require no JSON parse", 0, reads.get());
        assertNotSame(imported, persister.loadAllAccounts().get("Alice"));
        assertEquals("Later normal reload parses the written working snapshot", 1, reads.get());
    }

    @Test
    public void strictReadHandsTheSameParsedModelToFirstLoadThenResumesOrdinaryReads() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SandboxTradePersister persister = new SandboxTradePersister(countingGson(reads), temporary.getRoot());
        write("Alice.json", "{\"version\":1}");
        Map<String, AccountData> metadata = persister.preloadAccounts();
        AccountData parsed = metadata.get("Alice");
        metadata.clear();
        assertEquals(1, reads.get());
        assertSame(parsed, persister.loadAllAccounts().get("Alice"));
        assertEquals("Initial load must not parse again", 1, reads.get());
        assertNotSame(parsed, persister.loadAllAccounts().get("Alice"));
        assertEquals("Later reloads still read disk", 2, reads.get());
    }

    @Test
    public void corruptPrimaryUsesProductionBackupOnceAndKeepsTheRecoveredModel() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SandboxTradePersister persister = new SandboxTradePersister(countingGson(reads), temporary.getRoot());
        write("Alice.json", "{");
        write("Alice.backup.json", "{\"version\":1}");
        AccountData recovered = persister.preloadAccounts().get("Alice");
        assertNotNull(recovered);
        assertFalse(persister.isAccountProtected("Alice"));
        assertEquals(2, reads.get());
        assertSame(recovered, persister.loadAllAccounts().get("Alice"));
        assertEquals("No second primary/backup parse", 2, reads.get());
    }

    @Test
    public void unreadablePrimaryAndBackupFailStrictImportAndRemainWriteProtected() throws Exception {
        SandboxTradePersister persister = new SandboxTradePersister(new Gson(), temporary.getRoot());
        write("Alice.json", "{");
        write("Alice.backup.json", "{");
        try { persister.preloadAccounts(); fail("Corrupt imports must fail"); }
        catch (IllegalStateException expected) { assertTrue(persister.isAccountProtected("Alice")); }
        try { persister.writeToFile("Alice", new AccountData()); fail("Protection must survive preload failure"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("unreadable")); }
        assertEquals("{", Files.readString(temporary.getRoot().toPath().resolve("Alice.json")));
    }

    private void write(String name, String contents) throws IOException {
        Files.write(temporary.getRoot().toPath().resolve(name), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static Gson countingGson(AtomicInteger reads) {
        return new GsonBuilder().registerTypeAdapterFactory(new TypeAdapterFactory() {
            @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                if (type.getRawType() != AccountData.class) return null;
                TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
                return new TypeAdapter<T>() {
                    @Override public void write(JsonWriter writer, T value) throws IOException { delegate.write(writer, value); }
                    @Override public T read(JsonReader reader) throws IOException {
                        reads.incrementAndGet();
                        return delegate.read(reader);
                    }
                };
            }
        }).create();
    }
}
