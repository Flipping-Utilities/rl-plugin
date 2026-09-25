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
import static org.junit.Assert.assertNull;
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
 * - completed and archived filled offers persist as trades; active fills persist in slots,
 * - restored flip profit exactly matches the JSON path's flip computation,
 * - recipe-flip and favorite counts match the source data,
 * - unresolved offer references survive, without orphaned recipe rows or invalid consumption,
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

    /** Active partials are restored from slots, so only archived partials need trade rows. */
    private static long expectedTradeRows(AccountData data) {
        Set<String> activeUuids = new HashSet<>();
        if (data.getLastOffers() != null) {
            for (OfferEvent offer : data.getLastOffers().values()) {
                if (offer != null && !offer.isComplete() && !offer.isCausedByEmptySlot()
                    && offer.getUuid() != null) {
                    activeUuids.add(offer.getUuid());
                }
            }
        }
        long rows = 0;
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || offer.isCausedByEmptySlot()) continue;
                if (!offer.isComplete() && (offer.getCurrentQuantityInTrade() <= 0
                    || activeUuids.contains(offer.getUuid()))) continue;
                rows++;
            }
        }
        return rows;
    }

    /**
     * Computes displayed flip profit from recipe-adjusted offers using HistoryManager.
     */
    private static long expectedFlipProfit(AccountData data) {
        Map<String, Integer> consumption = recipeConsumptionByUuid(data);
        long sum = 0;
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            List<OfferEvent> valid = new ArrayList<>();
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || offer.isCausedByEmptySlot()) continue;
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

    /** Compare restored history independently of which SQLite table holds each snapshot. */
    private static Map<List<Object>, Integer> historySnapshots(AccountData data) {
        Map<List<Object>, Integer> snapshots = new HashMap<>();
        for (FlippingItem item : data.getTrades()) {
            if (item == null || item.getHistory() == null) continue;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer == null || offer.isCausedByEmptySlot()
                    || (!offer.isComplete() && offer.getCurrentQuantityInTrade() <= 0)) continue;
                List<Object> snapshot = Arrays.asList(item.getItemId(), offer.getUuid(), offer.isBuy(),
                    offer.getCurrentQuantityInTrade(), offer.getPreTaxPrice(), offer.getState(),
                    offer.getSlot(), offer.getTotalQuantityInTrade(),
                    offer.getTime() == null ? null : offer.getTime().toEpochMilli());
                snapshots.merge(snapshot, 1, Integer::sum);
            }
        }
        return snapshots;
    }

    private static long expectedRecipeFlips(AccountData data) {
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

    private static void assertRecipeParity(String account, AccountData expected, AccountData actual) {
        for (RecipeFlipGroup group : expected.getRecipeFlipGroups()) {
            if (group.getRecipeFlips().isEmpty()) continue;
            RecipeFlipGroup restoredGroup = actual.getRecipeFlipGroups().stream()
                .filter(candidate -> java.util.Objects.equals(group.getRecipeKey(), candidate.getRecipeKey()))
                .findFirst().orElseThrow(() -> new AssertionError(account + ": missing recipe " + group.getRecipeKey()));
            for (RecipeFlip flip : group.getRecipeFlips()) {
                if (flip.getTimeOfCreation() == null) continue;
                String context = account + ": " + group.getRecipeKey() + " at " + flip.getTimeOfCreation();
                RecipeFlip restored = restoredGroup.getRecipeFlips().stream()
                    .filter(candidate -> candidate.getTimeOfCreation().toEpochMilli() == flip.getTimeOfCreation().toEpochMilli())
                    .findFirst().orElseThrow(() -> new AssertionError(context + ": missing flip"));
                assertRecipeComponents(context + " inputs", flip.getInputs(), restored.getInputs());
                assertRecipeComponents(context + " outputs", flip.getOutputs(), restored.getOutputs());
                assertEquals(context + " coin cost", flip.getCoinCost(), restored.getCoinCost());
                assertEquals(context + " missing offer data", flip.hasMissingOffers(), restored.hasMissingOffers());
                if (!flip.hasMissingOffers()) {
                    assertEquals(context + " expense", flip.getExpense(), restored.getExpense());
                    assertEquals(context + " revenue", flip.getRevenue(), restored.getRevenue());
                    assertEquals(context + " profit", flip.getProfit(), restored.getProfit());
                    assertEquals(context + " tax", flip.getTaxPaid(), restored.getTaxPaid());
                }
            }
        }
    }

    private static void assertRecipeComponents(String context, Map<Integer, Map<String, PartialOffer>> expected,
                                               Map<Integer, Map<String, PartialOffer>> actual) {
        long componentCount = 0;
        for (Map.Entry<Integer, Map<String, PartialOffer>> item : expected.entrySet()) {
            for (PartialOffer component : item.getValue().values()) {
                if (component == null || component.getAmountConsumed() <= 0) continue;
                componentCount++;
                String detail = context + " item=" + item.getKey() + " uuid=" + component.getOfferUuid();
                assertNotNull(detail + " missing item", actual.get(item.getKey()));
                PartialOffer restored = actual.get(item.getKey()).get(component.getOfferUuid());
                assertNotNull(detail + " missing component", restored);
                assertEquals(detail + " UUID", component.getOfferUuid(), restored.getOfferUuid());
                assertEquals(detail + " consumed", component.getAmountConsumed(), restored.getAmountConsumed());
                OfferEvent before = component.getOffer();
                OfferEvent after = restored.getOffer();
                if (before == null) {
                    assertNull(detail + " unresolved offer must stay unresolved", after);
                    continue;
                }
                assertNotNull(detail + " missing restored offer", after);
                assertEquals(detail + " price", before.getPreTaxPrice(), after.getPreTaxPrice());
                assertEquals(detail + " original quantity", before.getCurrentQuantityInTrade(), after.getCurrentQuantityInTrade());
                assertEquals(detail + " item", before.getItemId(), after.getItemId());
                assertEquals(detail + " side", before.isBuy(), after.isBuy());
                assertEquals(detail + " state", before.getState(), after.getState());
                assertEquals(detail + " time (milliseconds)", before.getTime().toEpochMilli(), after.getTime().toEpochMilli());
            }
        }
        assertEquals(context + " component count", componentCount, actual.values().stream().mapToLong(Map::size).sum());
    }

    private static long expectedFavoriteRows(AccountData data) {
        Set<Integer> favorited = new HashSet<>();
        for (FlippingItem item : data.getTrades()) {
            if (item != null && item.isFavorite()) favorited.add(item.getItemId());
        }
        return favorited.size();
    }

    /** Visibility records preserve every source item, including hidden items without history. */
    private static long expectedLoadedItemCount(AccountData data) {
        Set<Integer> itemIds = new HashSet<>();
        for (FlippingItem item : data.getTrades()) {
            if (item != null) {
                itemIds.add(item.getItemId());
            }
        }
        return itemIds.size();
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
            public Map<String, AccountData> loadAllAccountsForMigration() {
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
            assertEquals("trade rows must match completed + archived filled offers for " + name, expectedTradeRows(data), tradeRows);

            AccountData loaded = storage.loadAccount(name);
            assertNotNull("loadAccount must return data for " + name, loaded);
            assertEquals("Restored history must include active and archived fills for " + name,
                historySnapshots(data), historySnapshots(loaded));
            assertRecipeParity(name, data, loaded);
            long flipProfit = expectedFlipProfit(loaded);
            assertEquals("Restored flip profit must match the JSON flip computation for " + name,
                expectedFlipProfit(data), flipProfit);

            long recipeFlips = q("SELECT COUNT(*) FROM recipe_flips rf JOIN accounts a ON a.id = rf.account_id WHERE a.display_name = ?", name);
            assertEquals("recipe flips must match source for " + name, expectedRecipeFlips(data), recipeFlips);

            long favoriteRows = q("SELECT COUNT(*) FROM item_favorites f JOIN accounts a ON a.id = f.account_id WHERE a.display_name = ?", name);
            assertEquals("favorites must be migrated for " + name, expectedFavoriteRows(data), favoriteRows);

            assertEquals("loadAccount item count for " + name, expectedLoadedItemCount(data), loaded.getTrades().size());

            System.out.println("[RealData] verified " + name + ": " + tradeRows + " trades, flip profit "
                + flipProfit + ", " + recipeFlips + " recipe flips, " + loaded.getTrades().size() + " loaded items");
        }

        // Components are self-contained: their source offer need not remain in trade history.
        for (String table : Arrays.asList("recipe_flip_inputs", "recipe_flip_outputs")) {
            assertEquals("no component may reference a missing recipe flip in " + table, 0L,
                q("SELECT COUNT(*) FROM " + table + " component LEFT JOIN recipe_flips rf " +
                    "ON rf.id = component.recipe_flip_id WHERE rf.id IS NULL"));
            assertEquals("consumed quantities must be positive in " + table, 0L,
                q("SELECT COUNT(*) FROM " + table + " WHERE amount_consumed <= 0"));
        }

        // --- forced re-run (retry path: flags cleared) must be a complete no-op ---
        long tradesBefore = q("SELECT COUNT(*) FROM trades");
        long recipesBefore = q("SELECT COUNT(*) FROM recipe_flips");
        long inputsBefore = q("SELECT COUNT(*) FROM recipe_flip_inputs");
        long outputsBefore = q("SELECT COUNT(*) FROM recipe_flip_outputs");
        for (String name : accounts.keySet()) {
            storage.clearSetting("migrated_" + name);
        }
        storage.clearSetting("migration_completed");
        storage.clearSetting("migration_completed_at");
        new MigrationService(storage, stub).migrate();
        assertEquals("re-run must not add trades", tradesBefore, q("SELECT COUNT(*) FROM trades"));
        assertEquals("re-run must not add recipe flips", recipesBefore, q("SELECT COUNT(*) FROM recipe_flips"));
        assertEquals("re-run must not add recipe inputs", inputsBefore, q("SELECT COUNT(*) FROM recipe_flip_inputs"));
        assertEquals("re-run must not add recipe outputs", outputsBefore, q("SELECT COUNT(*) FROM recipe_flip_outputs"));
        for (Map.Entry<String, AccountData> account : accounts.entrySet()) {
            assertRecipeParity(account.getKey(), account.getValue(), storage.loadAccount(account.getKey()));
        }

        // --- source files untouched ---
        for (Map.Entry<String, String> entry : checksumsBefore.entrySet()) {
            File file = "accountwide.json".equals(entry.getKey())
                ? new File(REALDATA_DIR, "accountwide.json")
                : new File(REALDATA_DIR, entry.getKey());
            assertEquals("source JSON must not be modified: " + entry.getKey(), entry.getValue(), sha256(file));
        }
    }
}
