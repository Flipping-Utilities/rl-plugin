package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end migration test against the REAL account JSON files in
 * {@code src/test/resources/realdata} (gitignored developer data; the test is skipped when
 * they are absent, e.g. on CI).
 *
 * The full production path is exercised: {@link MigrationService#migrate()} over every
 * account file, then verified:
 * - migration completes and flags every account,
 * - one trade row per complete offer (uuid dedupe must not lose real rows),
 * - flip-event profit exactly matches the JSON path's flip computation,
 * - recipe-flip and favorite counts match the source data,
 * - no dangling / negative consumption rows,
 * - loadAccount round-trips every account,
 * - a forced re-run (flags cleared, i.e. the partial-failure retry path) inserts nothing new,
 * - the source JSON files are never modified.
 */
public class RealDataMigrationTest {

    private static final File REALDATA_DIR = new File("src/test/resources/realdata");

    private Path tempDir;
    private SqliteStorage storage;

    @Before
    public void setUp() throws Exception {
        boolean hasData = REALDATA_DIR.isDirectory() && Arrays.stream(REALDATA_DIR.listFiles())
            .anyMatch(f -> f.getName().endsWith(".json")
                && !f.getName().endsWith(".backup.json")
                && !f.getName().equals("accountwide.json"));
        assumeTrue("realdata fixtures not present; skipping", hasData);
        tempDir = Files.createTempDirectory("realdata_migration_");
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

    /**
     * Instant adapter matching every format present in the real account files: epoch-millis
     * numbers, Gson's reflective {"seconds":..,"nanos":..} object form (written by older
     * plugin builds), and ISO-8601 strings.
     */
    private static Gson readGson() {
        return new GsonBuilder().registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter out, Instant value) throws java.io.IOException {
                if (value == null) { out.nullValue(); return; }
                out.value(value.toEpochMilli());
            }

            @Override
            public Instant read(JsonReader in) throws java.io.IOException {
                JsonToken token = in.peek();
                if (token == JsonToken.NULL) { in.nextNull(); return null; }
                if (token == JsonToken.NUMBER) return Instant.ofEpochMilli(in.nextLong());
                if (token == JsonToken.STRING) {
                    String s = in.nextString();
                    if (s == null || s.trim().isEmpty()) return null;
                    return Instant.parse(s);
                }
                if (token == JsonToken.BEGIN_OBJECT) {
                    in.beginObject();
                    long seconds = 0L;
                    long nanos = 0L;
                    while (in.hasNext()) {
                        String name = in.nextName();
                        if ("seconds".equals(name)) {
                            seconds = in.nextLong();
                        } else if ("nanos".equals(name)) {
                            nanos = in.nextLong();
                        } else {
                            in.skipValue();
                        }
                    }
                    in.endObject();
                    return Instant.ofEpochSecond(seconds, nanos);
                }
                in.skipValue();
                return null;
            }
        }).create();
    }

    private static AccountData loadAccountFile(File file, Gson gson) throws Exception {
        try (java.io.BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
             JsonReader jsonReader = new JsonReader(reader)) {
            return gson.fromJson(jsonReader, AccountData.class);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private long q(String sql, Object... params) throws SQLException {
        Connection conn = storage.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Object p : params) ps.setObject(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Map<String, Integer> recipeConsumptionByUuid(AccountData data) {
        Map<String, Integer> consumption = new HashMap<>();
        if (data.getRecipeFlipGroups() == null) return consumption;
        for (RecipeFlipGroup group : data.getRecipeFlipGroups()) {
            if (group == null || group.getRecipeFlips() == null) continue;
            for (RecipeFlip flip : group.getRecipeFlips()) {
                if (flip == null) continue;
                for (PartialOffer po : flip.getPartialOffers()) {
                    if (po != null && po.getOfferUuid() != null && po.getAmountConsumed() > 0) {
                        consumption.merge(po.getOfferUuid(), po.getAmountConsumed(), Integer::sum);
                    }
                }
            }
        }
        return consumption;
    }

    /** Mirrors MigrationService.migrateAccountBatched's trade-row counting: one row per complete offer. */
    private static long expectedTradeRows(AccountData data) {
        long rows = 0;
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) continue;
                rows++;
            }
        }
        return rows;
    }

    /**
     * Mirrors MigrationService.migrateFlipsBatched's computation (recipe-adjusted quantities,
     * madeBy set, HistoryManager.getFlips) so the sum can be compared 1:1 with the profit of
     * the flip events the migration actually inserted.
     */
    private static long expectedFlipProfit(AccountData data) {
        Map<String, Integer> consumption = recipeConsumptionByUuid(data);
        long sum = 0;
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            List<OfferEvent> valid = new ArrayList<>();
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) continue;
                Integer consumed = consumption.get(offer.getUuid());
                int qty = offer.getCurrentQuantityInTrade() - (consumed == null ? 0 : consumed);
                if (qty <= 0) continue;
                if (consumed != null && consumed > 0) {
                    OfferEvent adjusted = offer.clone();
                    adjusted.setCurrentQuantityInTrade(qty);
                    valid.add(adjusted);
                } else {
                    valid.add(offer);
                }
            }
            if (valid.isEmpty()) continue;

            List<OfferEvent> cloned = new ArrayList<>();
            for (OfferEvent offer : valid) {
                OfferEvent clone = offer.clone();
                clone.setMadeBy("migrated");
                cloned.add(clone);
            }
            List<Flip> flips = HistoryManager.getFlips(cloned);
            if (flips == null) continue;
            for (Flip flip : flips) {
                if (flip == null || flip.getTime() == null) continue;
                sum += (long) (flip.getSellPrice() - flip.getBuyPrice()) * flip.getQuantity();
            }
        }
        return sum;
    }

    private static long expectedRecipeEvents(AccountData data) {
        long count = 0;
        if (data.getRecipeFlipGroups() == null) return 0;
        for (RecipeFlipGroup group : data.getRecipeFlipGroups()) {
            if (group == null || group.getRecipeFlips() == null) continue;
            for (RecipeFlip flip : group.getRecipeFlips()) {
                if (flip != null && flip.getTimeOfCreation() != null) count++;
            }
        }
        return count;
    }

    private static long expectedFavoriteRows(AccountData data) {
        Set<Integer> favorited = new HashSet<>();
        for (FlippingItem item : data.getTrades()) {
            if (item != null && item.isFavorite()) favorited.add(item.getItemId());
        }
        return favorited.size();
    }

    /**
     * Items that should materialize from loadAccount: any item with at least one trade row
     * (qty-0/recipe-consumed rows included, matching loadTradeItems), PLUS favorite-only
     * items (favorited from search, never traded) which loadAccount recreates.
     */
    private static long expectedLoadedItemCount(AccountData data) {
        Set<Integer> itemsWithTrades = new HashSet<>();
        Set<Integer> favorited = new HashSet<>();
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            if (item.isFavorite()) {
                favorited.add(item.getItemId());
            }
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) continue;
                itemsWithTrades.add(item.getItemId());
                break;
            }
        }
        long favoriteOnly = favorited.stream()
            .filter(id -> !itemsWithTrades.contains(id))
            .count();
        return itemsWithTrades.size() + favoriteOnly;
    }

    @Test
    public void migrateAllRealAccountsEndToEnd() throws Exception {
        Gson gson = readGson();

        // --- load every real account file ---
        Map<String, AccountData> accounts = new LinkedHashMap<>();
        File[] accountFiles = REALDATA_DIR.listFiles((dir, name) ->
            name.endsWith(".json") && !name.endsWith(".backup.json") && !name.equals("accountwide.json"));
        Arrays.sort(accountFiles);
        Map<String, String> checksumsBefore = new HashMap<>();
        for (File file : accountFiles) {
            checksumsBefore.put(file.getName(), sha256(file));
            long start = System.currentTimeMillis();
            AccountData data = loadAccountFile(file, gson);
            assertNotNull("failed to load " + file.getName(), data);
            String displayName = file.getName().split("\\.")[0];
            accounts.put(displayName, data);
            System.out.println("[RealData] loaded " + file.getName() + " ("
                + (file.length() / 1024 / 1024) + " MB, "
                + (data.getTrades() == null ? 0 : data.getTrades().size()) + " items, "
                + (System.currentTimeMillis() - start) + " ms)");
        }
        AccountWideData loadedAccountWide = null;
        File accountWideFile = new File(REALDATA_DIR, "accountwide.json");
        if (accountWideFile.exists()) {
            checksumsBefore.put("accountwide.json", sha256(accountWideFile));
            try (java.io.BufferedReader reader = Files.newBufferedReader(accountWideFile.toPath(), StandardCharsets.UTF_8);
                 JsonReader jsonReader = new JsonReader(reader)) {
                loadedAccountWide = gson.fromJson(jsonReader, AccountWideData.class);
            }
        }
        final AccountWideData finalAccountWide = loadedAccountWide != null ? loadedAccountWide : new AccountWideData();

        // --- run the production migration against a temp DB ---
        storage = new SqliteStorage(new File(tempDir.toFile(), "realdata.db"));
        TradePersister stub = new TradePersister(gson) {
            @Override
            public Map<String, AccountData> loadAllAccounts() {
                return accounts;
            }

            @Override
            public AccountWideData loadAccountWideData() {
                return finalAccountWide;
            }
        };
        long migrationStart = System.currentTimeMillis();
        new MigrationService(storage, stub).migrate();
        System.out.println("[RealData] migration took " + (System.currentTimeMillis() - migrationStart) + " ms");

        // --- verification ---
        assertEquals("migration must complete for all real accounts",
            "true", storage.getSetting("migration_completed"));

        for (Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            String name = entry.getKey();
            AccountData data = entry.getValue();
            assertNotNull("migrated_ flag missing for " + name, storage.getSetting("migrated_" + name));

            long tradeRows = q("SELECT COUNT(*) FROM trades t JOIN accounts a ON a.id = t.account_id WHERE a.display_name = ?", name);
            assertEquals("trade rows must match complete offers for " + name, expectedTradeRows(data), tradeRows);

            long flipProfit = q("SELECT COALESCE(SUM(e.profit), 0) FROM events e JOIN accounts a ON a.id = e.account_id WHERE a.display_name = ? AND e.type = 'flip'", name);
            assertEquals("flip-event profit must match the JSON flip computation for " + name,
                expectedFlipProfit(data), flipProfit);

            long recipeEvents = q("SELECT COUNT(*) FROM events e JOIN accounts a ON a.id = e.account_id WHERE a.display_name = ? AND e.type = 'recipe'", name);
            assertEquals("recipe events must match source recipe flips for " + name, expectedRecipeEvents(data), recipeEvents);

            long favoriteRows = q("SELECT COUNT(*) FROM item_favorites f JOIN accounts a ON a.id = f.account_id WHERE a.display_name = ?", name);
            assertEquals("favorites must be migrated for " + name, expectedFavoriteRows(data), favoriteRows);

            AccountData loaded = storage.loadAccount(name);
            assertNotNull("loadAccount must return data for " + name, loaded);
            assertEquals("loadAccount item count for " + name, expectedLoadedItemCount(data), loaded.getTrades().size());

            System.out.println("[RealData] verified " + name + ": " + tradeRows + " trades, flip profit "
                + flipProfit + ", " + recipeEvents + " recipe events, " + loaded.getTrades().size() + " loaded items");
        }

        // --- referential / consumption integrity across all accounts ---
        assertEquals("no consumed_trade row may reference a missing event", 0L,
            q("SELECT COUNT(*) FROM consumed_trade ct LEFT JOIN events e ON e.id = ct.event_id WHERE e.id IS NULL"));
        assertEquals("no consumed_trade row may reference a missing trade", 0L,
            q("SELECT COUNT(*) FROM consumed_trade ct LEFT JOIN trades t ON t.id = ct.trade_id WHERE t.id IS NULL"));
        assertEquals("no trade may be over-consumed", 0L,
            q("SELECT COUNT(*) FROM trades t LEFT JOIN (SELECT trade_id, SUM(qty) AS c FROM consumed_trade GROUP BY trade_id) ct " +
                "ON ct.trade_id = t.id WHERE t.qty - COALESCE(ct.c, 0) < 0"));
        assertEquals("consumed quantities must be positive", 0L,
            q("SELECT COUNT(*) FROM consumed_trade WHERE qty <= 0"));

        // --- forced re-run (retry path: flags cleared) must be a complete no-op ---
        long tradesBefore = q("SELECT COUNT(*) FROM trades");
        long eventsBefore = q("SELECT COUNT(*) FROM events");
        long consumedBefore = q("SELECT COUNT(*) FROM consumed_trade");
        for (String name : accounts.keySet()) {
            storage.clearSetting("migrated_" + name);
        }
        storage.clearSetting("migration_completed");
        storage.clearSetting("migration_completed_at");
        new MigrationService(storage, stub).migrate();
        assertEquals("re-run must not add trades", tradesBefore, q("SELECT COUNT(*) FROM trades"));
        assertEquals("re-run must not add events", eventsBefore, q("SELECT COUNT(*) FROM events"));
        assertEquals("re-run must not add consumption rows", consumedBefore, q("SELECT COUNT(*) FROM consumed_trade"));

        // --- source files untouched ---
        for (Map.Entry<String, String> entry : checksumsBefore.entrySet()) {
            File file = "accountwide.json".equals(entry.getKey())
                ? new File(REALDATA_DIR, "accountwide.json")
                : new File(REALDATA_DIR, entry.getKey());
            assertEquals("source JSON must not be modified: " + entry.getKey(), entry.getValue(), sha256(file));
        }
    }
}
