package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bridges the live model and journal records without adopting unseen changes from another client. */
public final class JsonStorage {
    private static final String ACCOUNT_WIDE = "accountwide";
    private final Path storageDirectory;
    private final File dataDirectory;
    private final Gson gson;
    private final JsonStorageCodec codec;
    private final JsonJournalStore store;
    // This is the data the caller adopted, not necessarily the latest data on disk.
    private Map<String, JsonElement> baseline;

    public JsonStorage(Gson gson) {
        this(gson, TradePersister.PARENT_DIRECTORY);
    }

    public JsonStorage(Gson gson, File dataDirectory) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "data directory");
        storageDirectory = dataDirectory.toPath().resolve("json-v2").toAbsolutePath().normalize();
        codec = new JsonStorageCodec(gson);
        store = new JsonJournalStore(storageDirectory, gson);
    }

    public Path getStorageDirectory() {
        return storageDirectory;
    }

    /** Imports valid legacy JSON once, leaving every source file unchanged. */
    public synchronized LoadedData load() throws IOException {
        if (!store.exists()) {
            Files.createDirectories(dataDirectory.toPath());
            TradePersister legacy = new TradePersister(gson, dataDirectory);
            Map<String, JsonElement> initial = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, AccountData> account : legacy.loadAllAccountsForMigration().entrySet()) {
                    initial.putAll(codec.encodeAccount(account.getKey(), account.getValue()));
                }
                initial.put(ACCOUNT_WIDE, codec.encodeAccountWide(legacy.loadAccountWideData()));
                // Validate the whole import before publishing either checkpoint.
                decode(initial);
            } catch (RuntimeException invalidLegacy) {
                throw new IOException("Cannot migrate legacy JSON; source files were preserved", invalidLegacy);
            }
            store.initialize(initial);
        }
        Map<String, JsonElement> current = store.load();
        LoadedData loaded = decode(current);
        baseline = new LinkedHashMap<>(current);
        return loaded;
    }

    /** Returns null for a deleted or unknown account and adopts only this account's revision. */
    public synchronized AccountData loadAccount(String displayName) throws IOException {
        requireLoaded();
        Map<String, JsonElement> current = store.load();
        String prefix = JsonStorageCodec.accountPrefix(displayName);
        AccountData account;
        try {
            account = codec.accountNames(current).contains(displayName)
                ? codec.decodeAccount(displayName, current) : null;
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot load JSON account " + displayName, invalid);
        }
        replacePrefix(baseline, current, prefix);
        return account;
    }

    public synchronized AccountWideData loadAccountWideData() throws IOException {
        requireLoaded();
        Map<String, JsonElement> current = store.load();
        AccountWideData wide = decodeAccountWide(current);
        replaceAccountWide(baseline, current);
        return wide;
    }

    /** A catalog check does not imply that any account's live model has adopted disk changes. */
    public synchronized Set<String> accountNames() throws IOException {
        requireLoaded();
        try {
            return new LinkedHashSet<>(codec.accountNames(store.load()));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read JSON account catalog", invalid);
        }
    }

    /** Reads changed models without claiming that the caller has accepted them. */
    public synchronized Updates readUpdates() throws IOException {
        requireLoaded();
        Map<String, JsonElement> current = store.load();
        try {
            Set<String> present = codec.accountNames(current);
            Set<String> names = new LinkedHashSet<>(codec.accountNames(baseline));
            names.addAll(present);
            Map<String, Map<String, JsonElement>> oldGroups = accountGroups(baseline);
            Map<String, Map<String, JsonElement>> newGroups = accountGroups(current);
            Map<String, AccountData> changed = new LinkedHashMap<>();
            Set<String> deleted = new LinkedHashSet<>();
            for (String name : names) {
                String prefix = JsonStorageCodec.accountPrefix(name);
                if (Objects.equals(oldGroups.get(prefix), newGroups.get(prefix))) continue;
                if (present.contains(name)) changed.put(name, codec.decodeAccount(name, current));
                else deleted.add(name);
            }
            boolean wideChanged = !Objects.equals(baseline.get(ACCOUNT_WIDE), current.get(ACCOUNT_WIDE));
            AccountWideData wide = wideChanged ? decodeAccountWide(current) : null;
            return new Updates(this, current, changed, deleted, wide, wideChanged);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read changed JSON accounts", invalid);
        }
    }

    /** Adopts exactly the snapshot represented by accepted models, without further disk I/O. */
    public synchronized void adopt(Updates updates, Set<String> acceptedAccounts, boolean acceptAccountWide) {
        requireLoaded();
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(acceptedAccounts, "accepted accounts");
        if (updates.owner != this) throw new IllegalArgumentException("Updates belong to another JSON storage instance");
        for (String name : acceptedAccounts) {
            if (!updates.accounts.containsKey(name) && !updates.deletedAccounts.contains(name)) {
                throw new IllegalArgumentException("Account is not part of these updates: " + name);
            }
        }
        if (acceptAccountWide && !updates.accountWideChanged) {
            throw new IllegalArgumentException("Account-wide settings are not part of these updates");
        }
        for (String name : acceptedAccounts) {
            replacePrefix(baseline, updates.records, JsonStorageCodec.accountPrefix(name));
        }
        if (acceptAccountWide) replaceAccountWide(baseline, updates.records);
    }

    /**
     * Call while the live model is stable. JSON trees retain no references to live objects;
     * the resulting save can be committed on the storage worker after later model changes.
     */
    public synchronized CapturedSave capture(Map<String, AccountData> dirtyAccounts,
                                              AccountWideData wideOrNull,
                                              Set<String> deletedAccounts) {
        return capture(dirtyAccounts, Collections.emptyMap(), wideOrNull, deletedAccounts);
    }

    /** Session-only changes replace the account header without traversing historical offers. */
    public synchronized CapturedSave capture(Map<String, AccountData> dirtyAccounts,
                                              Map<String, AccountData> metadataOnlyAccounts,
                                              AccountWideData wideOrNull,
                                              Set<String> deletedAccounts) {
        requireLoaded();
        Objects.requireNonNull(dirtyAccounts, "dirty accounts");
        Objects.requireNonNull(metadataOnlyAccounts, "metadata-only accounts");
        Objects.requireNonNull(deletedAccounts, "deleted accounts");
        Set<String> touched = new LinkedHashSet<>(deletedAccounts);
        touched.addAll(dirtyAccounts.keySet());
        for (String name : dirtyAccounts.keySet()) {
            if (deletedAccounts.contains(name)) {
                throw new IllegalArgumentException("Cannot save and delete the same account: " + name);
            }
        }
        Map<String, JsonElement> desired = new LinkedHashMap<>(baseline);
        for (String name : touched) {
            String prefix = JsonStorageCodec.accountPrefix(name);
            desired.keySet().removeIf(key -> key.startsWith(prefix));
            if (dirtyAccounts.containsKey(name)) {
                desired.putAll(codec.encodeAccount(name, dirtyAccounts.get(name)));
            }
        }
        for (Map.Entry<String, AccountData> metadata : metadataOnlyAccounts.entrySet()) {
            String name = metadata.getKey();
            if (touched.contains(name)) continue;
            String key = JsonStorageCodec.accountPrefix(name) + "account";
            if (!baseline.containsKey(key)) {
                throw new IllegalArgumentException("A new account requires a full snapshot: " + name);
            }
            desired.put(key, codec.encodeAccountMetadata(name, metadata.getValue()));
            touched.add(name);
        }
        if (wideOrNull != null) {
            desired.put(ACCOUNT_WIDE, codec.encodeAccountWide(wideOrNull));
        }
        return new CapturedSave(this, baseline, desired, touched, wideOrNull != null);
    }

    /** Only successfully committed accounts advance the caller's adopted baseline. */
    public synchronized void commit(CapturedSave captured) throws IOException {
        Objects.requireNonNull(captured, "captured save");
        if (captured.owner != this) {
            throw new IllegalArgumentException("The save belongs to another JSON storage instance");
        }
        store.commit(captured.expected, captured.desired);
        for (String name : captured.accounts) {
            replacePrefix(baseline, captured.desired, JsonStorageCodec.accountPrefix(name));
        }
        if (captured.accountWideChanged) {
            replaceAccountWide(baseline, captured.desired);
        }
    }

    private LoadedData decode(Map<String, JsonElement> records) throws IOException {
        try {
            for (String key : records.keySet()) {
                if (!key.equals(ACCOUNT_WIDE) && !key.startsWith("accounts/")) {
                    throw new IllegalArgumentException("Unknown JSON record: " + key);
                }
            }
            Map<String, AccountData> accounts = new LinkedHashMap<>();
            for (String name : codec.accountNames(records)) {
                accounts.put(name, codec.decodeAccount(name, records));
            }
            return new LoadedData(accounts, decodeAccountWide(records));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot decode JSON storage; stored data was preserved", invalid);
        }
    }

    private AccountWideData decodeAccountWide(Map<String, JsonElement> records) throws IOException {
        try {
            JsonElement wide = records.get(ACCOUNT_WIDE);
            return wide == null ? new AccountWideData() : codec.decodeAccountWide(wide);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot decode account-wide JSON settings", invalid);
        }
    }

    private void requireLoaded() {
        if (baseline == null) {
            throw new IllegalStateException("Load JSON storage before accessing or changing it");
        }
    }

    private static void replacePrefix(Map<String, JsonElement> target,
                                      Map<String, JsonElement> source, String prefix) {
        target.keySet().removeIf(key -> key.startsWith(prefix));
        source.forEach((key, value) -> {
            if (key.startsWith(prefix)) target.put(key, value);
        });
    }

    private static void replaceAccountWide(Map<String, JsonElement> target, Map<String, JsonElement> source) {
        target.remove(ACCOUNT_WIDE);
        if (source.containsKey(ACCOUNT_WIDE)) target.put(ACCOUNT_WIDE, source.get(ACCOUNT_WIDE));
    }

    private static Map<String, Map<String, JsonElement>> accountGroups(Map<String, JsonElement> records) {
        Map<String, Map<String, JsonElement>> groups = new LinkedHashMap<>();
        records.forEach((key, value) -> {
            if (key.startsWith("accounts/")) {
                int end = key.indexOf('/', "accounts/".length());
                if (end >= 0) {
                    groups.computeIfAbsent(key.substring(0, end + 1), ignored -> new LinkedHashMap<>())
                        .put(key, value);
                }
            }
        });
        return groups;
    }

    public static final class Updates {
        private final JsonStorage owner;
        private final Map<String, JsonElement> records;
        private final Map<String, AccountData> accounts;
        private final Set<String> deletedAccounts;
        private final AccountWideData accountWideData;
        private final boolean accountWideChanged;

        private Updates(JsonStorage owner, Map<String, JsonElement> records,
                        Map<String, AccountData> accounts, Set<String> deletedAccounts,
                        AccountWideData accountWideData, boolean accountWideChanged) {
            this.owner = owner;
            this.records = Collections.unmodifiableMap(new LinkedHashMap<>(records));
            this.accounts = Collections.unmodifiableMap(new LinkedHashMap<>(accounts));
            this.deletedAccounts = Collections.unmodifiableSet(new LinkedHashSet<>(deletedAccounts));
            this.accountWideData = accountWideData;
            this.accountWideChanged = accountWideChanged;
        }

        public Map<String, AccountData> getAccounts() {
            return accounts;
        }

        public Set<String> getDeletedAccounts() {
            return deletedAccounts;
        }

        public AccountWideData getAccountWideData() {
            return accountWideData;
        }
    }

    public static final class LoadedData {
        private final Map<String, AccountData> accounts;
        private final AccountWideData accountWideData;

        private LoadedData(Map<String, AccountData> accounts, AccountWideData accountWideData) {
            this.accounts = accounts;
            this.accountWideData = accountWideData;
        }

        public Map<String, AccountData> getAccounts() {
            return accounts;
        }

        public AccountWideData getAccountWideData() {
            return accountWideData;
        }
    }

    public static final class CapturedSave {
        private final JsonStorage owner;
        private final Map<String, JsonElement> expected;
        private final Map<String, JsonElement> desired;
        private final Set<String> accounts;
        private final boolean accountWideChanged;

        private CapturedSave(JsonStorage owner, Map<String, JsonElement> expected,
                             Map<String, JsonElement> desired, Set<String> accounts,
                             boolean accountWideChanged) {
            this.owner = owner;
            this.expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
            this.desired = Collections.unmodifiableMap(new LinkedHashMap<>(desired));
            this.accounts = Collections.unmodifiableSet(new LinkedHashSet<>(accounts));
            this.accountWideChanged = accountWideChanged;
        }
    }
}
