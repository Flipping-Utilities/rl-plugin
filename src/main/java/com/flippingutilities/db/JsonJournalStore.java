package com.flippingutilities.db;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned JSON records, an atomic transaction per journal line, and two recoverable checkpoints. */
public final class JsonJournalStore {
    private static final Logger log = LoggerFactory.getLogger(JsonJournalStore.class);
    private static final int VERSION = 2;
    private static final long CHECKPOINT_BYTES = 8 * 1024 * 1024;
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final Gson gson;
    private final String recoveryName = "conflict-" + UUID.randomUUID() + ".json";
    private Map<String, JsonElement> records;
    private long revision;
    private long offset;
    private Object checkpointStamp;
    private Object journalIdentity;

    public JsonJournalStore(Path directory, Gson gson) {
        this.directory = directory.toAbsolutePath().normalize();
        this.gson = gson.newBuilder().serializeNulls().disableHtmlEscaping().create();
    }

    public boolean exists() {
        return Files.exists(directory.resolve("checkpoint.json"))
            || Files.exists(directory.resolve("checkpoint.previous.json"));
    }

    /** A competing first launch wins; its completed migration must never be replaced. */
    public void initialize(Map<String, JsonElement> initialRecords) throws IOException {
        locked(() -> {
            if (!exists()) {
                Path pending = directory.resolve("initializing");
                Path journal = directory.resolve("journal.jsonl");
                if (Files.exists(journal) && (!Files.exists(pending) || Files.size(journal) != 0)) {
                    throw new IOException("JSON checkpoints are missing; refusing to replace an existing journal");
                }
                try (FileChannel marker = FileChannel.open(pending,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    marker.force(true);
                }
                syncDirectory();
                // The journal must exist before either checkpoint makes the store discoverable.
                try (FileChannel channel = FileChannel.open(journal,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                JsonObject snapshot = checkpoint(0, initialRecords);
                writeAtomic(directory.resolve("checkpoint.previous.json"), snapshot);
                writeAtomic(directory.resolve("checkpoint.json"), snapshot);
                Files.deleteIfExists(pending);
                syncDirectory();
            }
            refresh();
            return null;
        });
    }

    /** Values are immutable snapshots: callers must replace entries rather than mutate JSON trees. */
    public Map<String, JsonElement> load() throws IOException {
        return locked(() -> {
            refresh();
            return new LinkedHashMap<>(records);
        });
    }

    /**
     * Compare and publish under one interprocess lock. Different accounts can commit independently;
     * stale changes to the same account are retained in a recovery file rather than silently merged.
     * All changed accounts and account-wide settings in this call share one commit boundary.
     */
    public Map<String, JsonElement> commit(Map<String, JsonElement> expected,
                                          Map<String, JsonElement> desired) throws IOException {
        return locked(() -> {
            refresh();
            Set<String> changedGroups = new HashSet<>();
            Set<String> keys = new HashSet<>(expected.keySet());
            keys.addAll(desired.keySet());
            for (String key : keys) {
                if (!Objects.equals(expected.get(key), desired.get(key))) {
                    changedGroups.add(group(key));
                }
            }
            for (String group : changedGroups) {
                Map<String, JsonElement> currentGroup = select(records, group);
                if (!currentGroup.equals(select(expected, group)) && !currentGroup.equals(select(desired, group))) {
                    JsonObject recovery = checkpoint(revision, desired);
                    Path recoveryFile = directory.resolve(recoveryName);
                    writeAtomic(recoveryFile, recovery);
                    throw new ConflictException(recoveryFile);
                }
            }
            JsonObject changes = new JsonObject();
            com.google.gson.JsonArray deleted = new com.google.gson.JsonArray();
            for (String group : changedGroups) {
                Map<String, JsonElement> replacement = select(desired, group);
                for (String key : select(records, group).keySet()) {
                    if (!replacement.containsKey(key)) {
                        deleted.add(key);
                    }
                }
                for (Map.Entry<String, JsonElement> entry : replacement.entrySet()) {
                    if (!Objects.equals(records.get(entry.getKey()), entry.getValue())) {
                        changes.add(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (changes.size() == 0 && deleted.size() == 0) {
                return new LinkedHashMap<>(records);
            }
            JsonObject transaction = new JsonObject();
            transaction.addProperty("formatVersion", VERSION);
            transaction.addProperty("revision", revision + 1);
            transaction.add("put", changes);
            transaction.add("delete", deleted);
            seal(transaction);
            java.io.StringWriter text = new java.io.StringWriter();
            gson.toJson(transaction, new com.google.gson.stream.JsonWriter(text));
            byte[] line = (text + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(directory.resolve("journal.jsonl"),
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            } catch (IOException failure) {
                invalidate();
                throw failure;
            }
            apply(transaction);
            offset += line.length;
            journalIdentity = identity(directory.resolve("journal.jsonl"));
            if (offset >= CHECKPOINT_BYTES) {
                try {
                    checkpointAndTrim();
                } catch (IOException maintenanceFailure) {
                    // The transaction is already durable. A failed checkpoint must not undo its acknowledgment.
                    invalidate();
                    log.warn("JSON checkpoint maintenance failed; journal remains authoritative", maintenanceFailure);
                }
            }
            return new LinkedHashMap<>(records == null ? reloadRecords() : records);
        });
    }

    private Map<String, JsonElement> reloadRecords() throws IOException {
        refresh();
        return records;
    }

    private void refresh() throws IOException {
        Path checkpointFile = directory.resolve("checkpoint.json");
        Path journal = directory.resolve("journal.jsonl");
        Object stamp = stamp(checkpointFile);
        Object identity = identity(journal);
        if (records == null || !Objects.equals(checkpointStamp, stamp)
                || !Objects.equals(journalIdentity, identity) || Files.size(journal) < offset) {
            JsonObject snapshot;
            try {
                snapshot = readEnvelope(checkpointFile);
            } catch (UnsupportedVersionException futureVersion) {
                throw futureVersion;
            } catch (IOException | RuntimeException invalidPrimary) {
                try {
                    snapshot = readEnvelope(directory.resolve("checkpoint.previous.json"));
                } catch (IOException | RuntimeException invalidBackup) {
                    invalidBackup.addSuppressed(invalidPrimary);
                    throw new IOException("Neither JSON checkpoint can be read; original files were preserved", invalidBackup);
                }
                log.warn("Recovering JSON from the previous checkpoint and transaction journal", invalidPrimary);
            }
            records = objectMap(snapshot.getAsJsonObject("records"));
            revision = snapshot.get("revision").getAsLong();
            offset = 0;
            checkpointStamp = stamp;
            journalIdentity = identity;
        }
        try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            file.seek(offset);
            while (file.getFilePointer() < file.length()) {
                long start = file.getFilePointer();
                String raw = file.readLine();
                long end = file.getFilePointer();
                file.seek(end - 1);
                boolean complete = file.read() == '\n';
                if (!complete) {
                    // A transaction has a newline only after its entire JSON payload has been written.
                    file.setLength(start);
                    file.getChannel().force(true);
                    log.warn("Discarded an interrupted, uncommitted JSON journal tail");
                    break;
                }
                String json = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                JsonObject transaction = parseEnvelope(json);
                long next = transaction.get("revision").getAsLong();
                if (next > revision) {
                    if (next != revision + 1) {
                        throw new IOException("Missing JSON transaction before revision " + next);
                    }
                    apply(transaction);
                }
                offset = end;
            }
        } catch (IOException | RuntimeException failure) {
            invalidate();
            throw new IOException("Cannot replay JSON journal; stored data was preserved", failure);
        }
        journalIdentity = identity(journal);
    }

    private void apply(JsonObject transaction) throws IOException {
        if (!transaction.has("put") || !transaction.has("delete")) {
            throw new IOException("Incomplete JSON transaction");
        }
        Map<String, JsonElement> updates = objectMap(transaction.getAsJsonObject("put"));
        for (JsonElement key : transaction.getAsJsonArray("delete")) {
            records.remove(key.getAsString());
        }
        records.putAll(updates);
        revision = transaction.get("revision").getAsLong();
    }

    private void checkpointAndTrim() throws IOException {
        JsonObject prior;
        try {
            prior = readEnvelope(directory.resolve("checkpoint.json"));
        } catch (IOException | RuntimeException invalidPrimary) {
            prior = readEnvelope(directory.resolve("checkpoint.previous.json"));
        }
        long previousRevision = prior.get("revision").getAsLong();
        // Keep the preceding checkpoint AND every transaction needed to replay from it.
        writeAtomic(directory.resolve("checkpoint.previous.json"), prior);
        writeAtomic(directory.resolve("checkpoint.json"), checkpoint(revision, records));
        Path temporary = Files.createTempFile(directory, "journal-", ".tmp");
        try {
            try (RandomAccessFile source = new RandomAccessFile(directory.resolve("journal.jsonl").toFile(), "r");
                 FileChannel target = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                String raw;
                while ((raw = source.readLine()) != null) {
                    String json = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                    if (parseEnvelope(json).get("revision").getAsLong() > previousRevision) {
                        ByteBuffer bytes = ByteBuffer.wrap((json + "\n").getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) {
                            target.write(bytes);
                        }
                    }
                }
                target.force(true);
            }
            atomicMove(temporary, directory.resolve("journal.jsonl"));
        } finally {
            Files.deleteIfExists(temporary);
        }
        invalidate();
    }

    private JsonObject checkpoint(long atRevision, Map<String, JsonElement> data) throws IOException {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("formatVersion", VERSION);
        snapshot.addProperty("revision", atRevision);
        JsonObject values = new JsonObject();
        data.forEach(values::add);
        snapshot.add("records", values);
        seal(snapshot);
        return snapshot;
    }

    private JsonObject readEnvelope(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                throw new IOException("Incomplete JSON file: " + path.getFileName());
            }
            return verify(parsed.getAsJsonObject());
        }
    }

    private JsonObject parseEnvelope(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new java.io.StringReader(json))) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                throw new IOException("Incomplete JSON transaction");
            }
            return verify(parsed.getAsJsonObject());
        }
    }

    private JsonObject verify(JsonObject envelope) throws IOException {
        if (!envelope.has("formatVersion") || envelope.get("formatVersion").getAsInt() != VERSION) {
            throw new UnsupportedVersionException();
        }
        if (!envelope.has("revision") || envelope.get("revision").getAsLong() < 0 || !envelope.has("checksum")) {
            throw new IOException("Missing JSON revision or checksum");
        }
        String expected = envelope.remove("checksum").getAsString();
        String actual = digest(envelope);
        envelope.addProperty("checksum", expected);
        if (!actual.equals(expected)) {
            throw new IOException("JSON checksum mismatch");
        }
        return envelope;
    }

    private void seal(JsonObject value) throws IOException {
        value.addProperty("checksum", digest(value));
    }

    private String digest(JsonObject value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Writer writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
                gson.toJson(value, new com.google.gson.stream.JsonWriter(writer));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 255));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void writeAtomic(Path destination, JsonObject value) throws IOException {
        Path temporary = Files.createTempFile(directory, "json-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 Writer writer = new OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
                writer.flush();
                channel.force(true);
            }
            atomicMove(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void atomicMove(Path from, Path to) throws IOException {
        // Unsupported atomic publication fails safely. Never silently weaken the commit protocol.
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory();
    }

    private void syncDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Some platforms (notably Windows) do not expose directory fsync through Java.
            log.debug("Directory synchronization is unavailable for JSON storage", unsupported);
        }
    }

    private <T> T locked(IoSupplier<T> action) throws IOException {
        Files.createDirectories(directory);
        synchronized (JVM_LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try (FileChannel channel = FileChannel.open(directory.resolve("store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        }
    }

    private void invalidate() {
        records = null;
        checkpointStamp = null;
        journalIdentity = null;
        offset = 0;
    }

    private static Object stamp(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return java.util.Arrays.asList(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private static Object identity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return attributes.fileKey() == null ? stamp(path) : attributes.fileKey();
    }

    private static Map<String, JsonElement> objectMap(JsonObject object) throws IOException {
        if (object == null) {
            throw new IOException("Missing JSON records");
        }
        Map<String, JsonElement> result = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static String group(String key) {
        if (key.startsWith("accounts/")) {
            int end = key.indexOf('/', "accounts/".length());
            if (end >= 0) {
                return key.substring(0, end + 1);
            }
        }
        return key;
    }

    private static Map<String, JsonElement> select(Map<String, JsonElement> values, String group) {
        Map<String, JsonElement> selected = new HashMap<>();
        values.forEach((key, value) -> {
            if (group(key).equals(group)) {
                selected.put(key, value);
            }
        });
        return selected;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    public static final class ConflictException extends IOException {
        public ConflictException(Path recoveryFile) {
            super("Another client changed this account. Local changes were preserved in " + recoveryFile);
        }
    }

    private static final class UnsupportedVersionException extends IOException {
        private UnsupportedVersionException() {
            super("Unsupported JSON storage version; refusing to read or replace it");
        }
    }
}
