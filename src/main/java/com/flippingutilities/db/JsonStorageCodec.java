package com.flippingutilities.db;

import com.flippingutilities.controller.RecipeHandler;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.runelite.api.GrandExchangeOfferState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts the live model into independently replaceable JSON records. Only persisted
 * domain fields are copied: timers, Swing objects and hydrated/derived values stay in memory.
 * History, active slots and recipe components retain independent offer snapshots because
 * a recipe or an occupied slot can outlive its source history entry.
 */
public final class JsonStorageCodec {
    private static final String ACCOUNTS = "accounts/";
    private static final int VERSION = 1;
    private final Gson gson;

    public JsonStorageCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson).newBuilder()
            .serializeNulls()
            .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                    if (value == null) out.nullValue();
                    else out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return null;
                    }
                    if (in.peek() != JsonToken.STRING) throw new IOException("Expected ISO-8601 timestamp");
                    return Instant.parse(in.nextString());
                }
            }).create();
    }

    public static String accountPrefix(String displayName) {
        require(displayName != null && !displayName.isEmpty(), "Missing account name");
        return ACCOUNTS + encode(displayName) + "/";
    }

    /** Normalizes missing legacy offer IDs once, using the model's existing exact-match rules. */
    public Map<String, JsonElement> encodeAccount(String displayName, AccountData data) {
        JsonElement metadata = encodeAccountMetadata(displayName, data);
        data.normalizeOfferIds();
        String prefix = accountPrefix(displayName);
        Map<String, JsonElement> records = new LinkedHashMap<>();
        records.put(prefix + "account", metadata);

        Set<String> historyIds = new LinkedHashSet<>();
        for (int i = 0; i < data.getTrades().size(); i++) {
            FlippingItem item = Objects.requireNonNull(data.getTrades().get(i), "item");
            ItemRecord row = new ItemRecord();
            row.position = i;
            row.itemId = item.getItemId();
            row.itemName = item.getItemName();
            row.visible = item.getValidFlippingPanelItem();
            row.favorite = item.isFavorite();
            row.favoriteCode = item.getFavoriteCode();
            row.nextGeLimitRefresh = item.getHistory().getNextGeLimitRefresh();
            row.itemsBoughtThisLimitWindow = item.getHistory().getItemsBoughtThisLimitWindow();
            row.itemsBoughtThroughCompleteOffers = item.getHistory().getItemsBoughtThroughCompleteOffers();
            put(records, prefix + "items/" + row.itemId, row);
            List<OfferEvent> history = item.getHistory().getCompressedOfferEvents();
            for (int position = 0; position < history.size(); position++) {
                OfferRecord offer = new OfferRecord();
                offer.position = position;
                offer.offer = snapshot(history.get(position));
                require(offer.offer.itemId.equals(row.itemId), "History offer belongs to another item");
                require(offer.offer.uuid != null && historyIds.add(offer.offer.uuid), "Duplicate or missing history offer UUID");
                put(records, historyKey(prefix, offer.offer), offer);
            }
        }
        for (Map.Entry<Integer, OfferEvent> slot : data.getLastOffers().entrySet()) {
            require(slot.getKey() != null && slot.getKey() >= 0 && slot.getKey() < 8, "Invalid slot index");
            put(records, prefix + "slots/" + slot.getKey(), snapshot(slot.getValue()));
        }
        for (int i = 0; i < data.getRecipeFlipGroups().size(); i++) {
            RecipeFlipGroup group = data.getRecipeFlipGroups().get(i);
            GroupRecord row = new GroupRecord();
            row.position = i;
            row.recipeKey = group.getRecipeKey();
            if (row.recipeKey == null && group.getRecipe() != null) {
                row.recipeKey = RecipeHandler.createRecipeKey(group.getRecipe());
            }
            String key = groupKey(prefix, row.recipeKey);
            put(records, key, row);
            for (int position = 0; position < group.getRecipeFlips().size(); position++) {
                RecipeFlip flip = group.getRecipeFlips().get(position);
                FlipRecord record = new FlipRecord();
                record.position = position;
                record.timeOfCreation = Objects.requireNonNull(flip.getTimeOfCreation(), "recipe timestamp");
                record.coinCost = flip.getCoinCost();
                record.inputs = components(flip.getInputs());
                record.outputs = components(flip.getOutputs());
                put(records, flipKey(key, record.timeOfCreation), record);
            }
        }
        return records;
    }

    /** Captures timer/session changes without traversing or normalizing trade history. */
    public JsonElement encodeAccountMetadata(String displayName, AccountData data) {
        accountPrefix(displayName);
        Objects.requireNonNull(data, "account");
        require(data.getVersion() == null || data.getVersion() <= AccountData.CURRENT_VERSION,
            "Unsupported account model version");
        AccountRecord account = new AccountRecord();
        account.formatVersion = VERSION;
        account.displayName = displayName;
        account.sessionStartTime = data.getSessionStartTime();
        account.accumulatedSessionTimeMillis = data.getAccumulatedSessionTimeMillis();
        account.lastSessionTimeUpdate = data.getLastSessionTimeUpdate();
        account.lastStoredAt = data.getLastStoredAt();
        account.lastModifiedAt = data.getLastModifiedAt();
        return gson.toJsonTree(account);
    }

    public AccountData decodeAccount(String displayName, Map<String, JsonElement> records) {
        String prefix = accountPrefix(displayName);
        JsonElement header = records.get(prefix + "account");
        require(header != null, "Missing account metadata for " + displayName);
        AccountRecord account = read(header, AccountRecord.class);
        require(account.formatVersion != null && account.formatVersion == VERSION, "Unsupported JSON account version");
        require(displayName.equals(account.displayName), "Account name does not match record key");
        require(account.accumulatedSessionTimeMillis != null, "Missing session duration");
        AccountData data = new AccountData();
        data.setVersion(AccountData.CURRENT_VERSION);
        data.setSessionStartTime(account.sessionStartTime);
        data.setAccumulatedSessionTimeMillis(account.accumulatedSessionTimeMillis);
        data.setLastSessionTimeUpdate(account.lastSessionTimeUpdate);
        data.setLastStoredAt(account.lastStoredAt);
        data.setLastModifiedAt(account.lastModifiedAt);
        Map<Integer, ItemRecord> items = new HashMap<>();
        Map<Integer, TreeMap<Integer, OfferEvent>> history = new HashMap<>();
        TreeMap<Integer, FlippingItem> orderedItems = new TreeMap<>();
        Map<String, GroupRecord> groups = new HashMap<>();
        Map<String, TreeMap<Integer, RecipeFlip>> flips = new HashMap<>();
        TreeMap<Integer, RecipeFlipGroup> orderedGroups = new TreeMap<>();
        Set<String> historyIds = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : records.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix) || key.equals(prefix + "account")) continue;
            String suffix = key.substring(prefix.length());
            if (suffix.startsWith("items/")) {
                ItemRecord row = read(entry.getValue(), ItemRecord.class);
                require(row.itemId != null && key.equals(prefix + "items/" + row.itemId), "Invalid item record identity");
                require(row.favorite != null && row.itemsBoughtThisLimitWindow != null
                    && row.itemsBoughtThroughCompleteOffers != null, "Incomplete item metadata");
                require(items.putIfAbsent(row.itemId, row) == null, "Duplicate item");
                FlippingItem item = new FlippingItem(row.itemId, row.itemName, 0, displayName);
                if (row.visible != null) item.setValidFlippingPanelItem(row.visible);
                item.setFavorite(row.favorite);
                item.setFavoriteCode(row.favoriteCode);
                item.getHistory().setNextGeLimitRefresh(row.nextGeLimitRefresh);
                item.getHistory().setItemsBoughtThisLimitWindow(row.itemsBoughtThisLimitWindow);
                item.getHistory().setItemsBoughtThroughCompleteOffers(row.itemsBoughtThroughCompleteOffers);
                ordered(orderedItems, row.position, item);
            } else if (suffix.startsWith("offers/")) {
                OfferRecord row = read(entry.getValue(), OfferRecord.class);
                OfferEvent offer = offer(row.offer);
                require(row.offer.uuid != null && key.equals(historyKey(prefix, row.offer)), "Invalid history record identity");
                require(historyIds.add(row.offer.uuid), "Duplicate history UUID");
                ordered(history.computeIfAbsent(offer.getItemId(), ignored -> new TreeMap<>()), row.position, offer);
            } else if (suffix.startsWith("slots/")) {
                int slot;
                try { slot = Integer.parseInt(suffix.substring("slots/".length())); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid slot record", e); }
                require(slot >= 0 && slot < 8 && key.equals(prefix + "slots/" + slot), "Invalid slot record identity");
                data.getLastOffers().put(slot, offer(read(entry.getValue(), Snapshot.class)));
            } else if (suffix.startsWith("recipes/")) {
                int split = key.indexOf("/flips/", prefix.length());
                if (split >= 0) {
                    String groupKey = key.substring(0, split);
                    FlipRecord row = read(entry.getValue(), FlipRecord.class);
                    require(row.timeOfCreation != null && row.coinCost != null
                        && key.equals(flipKey(groupKey, row.timeOfCreation)), "Invalid recipe flip identity");
                    RecipeFlip flip = new RecipeFlip(row.timeOfCreation, restoreComponents(row.outputs),
                        restoreComponents(row.inputs), row.coinCost);
                    ordered(flips.computeIfAbsent(groupKey, ignored -> new TreeMap<>()), row.position, flip);
                } else {
                    GroupRecord row = read(entry.getValue(), GroupRecord.class);
                    require(key.equals(groupKey(prefix, row.recipeKey)), "Invalid recipe group identity");
                    groups.put(key, row);
                    ordered(orderedGroups, row.position, new RecipeFlipGroup(row.recipeKey));
                }
            } else {
                throw new IllegalArgumentException("Unknown account record: " + key);
            }
        }
        require(items.keySet().containsAll(history.keySet()), "History references missing item metadata");
        require(groups.keySet().containsAll(flips.keySet()), "Recipe flip references missing group");
        data.getTrades().addAll(contiguous(orderedItems));
        for (FlippingItem item : data.getTrades()) {
            item.getHistory().getCompressedOfferEvents().addAll(contiguous(history.get(item.getItemId())));
        }
        data.getRecipeFlipGroups().addAll(contiguous(orderedGroups));
        for (RecipeFlipGroup group : data.getRecipeFlipGroups()) {
            group.getRecipeFlips().addAll(contiguous(flips.get(groupKey(prefix, group.getRecipeKey()))));
        }
        return data;
    }

    public Set<String> accountNames(Map<String, JsonElement> records) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : records.entrySet()) {
            if (!entry.getKey().startsWith(ACCOUNTS) || !entry.getKey().endsWith("/account")) continue;
            AccountRecord account = read(entry.getValue(), AccountRecord.class);
            require(account.formatVersion != null && account.formatVersion == VERSION, "Unsupported JSON account version");
            require(entry.getKey().equals(accountPrefix(account.displayName) + "account"), "Invalid account record identity");
            require(names.add(account.displayName), "Duplicate account");
        }
        for (String key : records.keySet()) {
            if (!key.startsWith(ACCOUNTS)) continue;
            int separator = key.indexOf('/', ACCOUNTS.length());
            require(separator >= 0 && records.containsKey(key.substring(0, separator + 1) + "account"),
                "Orphaned account record");
        }
        return names;
    }

    public JsonElement encodeAccountWide(AccountWideData data) {
        Objects.requireNonNull(data, "accountwide");
        JsonObject record = new JsonObject();
        record.addProperty("formatVersion", VERSION);
        record.add("data", gson.toJsonTree(data));
        return record;
    }

    public AccountWideData decodeAccountWide(JsonElement element) {
        require(element != null && element.isJsonObject(), "Expected accountwide record");
        JsonObject record = element.getAsJsonObject();
        require(record.size() == 2 && record.has("formatVersion") && record.has("data"), "Invalid accountwide record");
        require(record.get("formatVersion").equals(gson.toJsonTree(VERSION)), "Unsupported accountwide version");
        AccountWideData data = read(record.get("data"), AccountWideData.class);
        require(data.getOptions() != null && data.getSections() != null && data.getLocalRecipes() != null,
            "Incomplete accountwide data");
        return data;
    }

    private Snapshot snapshot(OfferEvent offer) {
        Objects.requireNonNull(offer, "offer");
        Snapshot row = new Snapshot();
        row.uuid = offer.getUuid();
        row.buy = offer.isBuy();
        row.itemId = offer.getItemId();
        row.quantity = offer.getCurrentQuantityInTrade();
        row.totalQuantity = offer.getTotalQuantityInTrade();
        row.price = offer.getPreTaxPrice();
        row.time = offer.getTime();
        row.slot = offer.getSlot();
        row.state = offer.getState();
        row.tickArrivedAt = offer.getTickArrivedAt();
        row.ticksSinceFirstOffer = offer.getTicksSinceFirstOffer();
        row.tradeStartedAt = offer.getTradeStartedAt();
        row.beforeLogin = offer.isBeforeLogin();
        return row;
    }

    private OfferEvent offer(Snapshot row) {
        require(row != null && row.buy != null && row.itemId != null && row.quantity != null
            && row.totalQuantity != null && row.price != null && row.slot != null && row.state != null
            && row.tickArrivedAt != null && row.ticksSinceFirstOffer != null && row.beforeLogin != null,
            "Incomplete offer snapshot");
        return new OfferEvent(row.uuid, row.buy, row.itemId, row.quantity, row.price,
            row.time, row.slot, row.state, row.tickArrivedAt, row.ticksSinceFirstOffer,
            row.totalQuantity, row.tradeStartedAt, row.beforeLogin, null, null, 0L, 0L);
    }

    private List<ComponentItem> components(Map<Integer, Map<String, PartialOffer>> source) {
        Objects.requireNonNull(source, "recipe components");
        List<ComponentItem> records = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, PartialOffer>> item : new TreeMap<>(source).entrySet()) {
            ComponentItem itemRecord = new ComponentItem();
            itemRecord.itemId = item.getKey();
            itemRecord.offers = new ArrayList<>();
            List<String> ids = new ArrayList<>(item.getValue().keySet());
            ids.sort(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
            for (String id : ids) {
                PartialOffer component = item.getValue().get(id);
                ComponentRecord row = new ComponentRecord();
                row.key = id;
                row.offerUuid = component.getOfferUuid();
                row.amountConsumed = component.getAmountConsumed();
                row.offer = component.getOffer() == null ? null : snapshot(component.getOffer());
                itemRecord.offers.add(row);
            }
            records.add(itemRecord);
        }
        return records;
    }

    private Map<Integer, Map<String, PartialOffer>> restoreComponents(List<ComponentItem> records) {
        require(records != null, "Missing recipe components");
        Map<Integer, Map<String, PartialOffer>> result = new LinkedHashMap<>();
        for (ComponentItem itemRecord : records) {
            require(itemRecord != null && itemRecord.itemId != null && itemRecord.offers != null,
                "Incomplete recipe component item");
            Map<String, PartialOffer> item = new LinkedHashMap<>();
            require(result.putIfAbsent(itemRecord.itemId, item) == null, "Duplicate recipe component item");
            for (ComponentRecord row : itemRecord.offers) {
                require(row != null && row.amountConsumed != null, "Incomplete recipe component");
                PartialOffer component = new PartialOffer(row.offerUuid, row.amountConsumed);
                component.setOffer(row.offer == null ? null : offer(row.offer));
                require(item.putIfAbsent(row.key, component) == null, "Duplicate recipe component");
            }
        }
        return result;
    }

    private <T> T read(JsonElement value, Class<T> type) {
        require(value != null && value.isJsonObject(), "Expected " + type.getSimpleName() + " object");
        T result = gson.fromJson(value, type);
        // Round-trip shape checking rejects unknown or missing fields rather than silently
        // discarding data written by a newer codec. Values are checked separately above.
        validateShape(value, gson.toJsonTree(result));
        return result;
    }

    private static void validateShape(JsonElement input, JsonElement decoded) {
        if (input.isJsonNull() || decoded.isJsonNull()) {
            require(input.isJsonNull() && decoded.isJsonNull(), "Unexpected null record field");
        } else if (input.isJsonObject() && decoded.isJsonObject()) {
            require(input.getAsJsonObject().keySet().equals(decoded.getAsJsonObject().keySet()),
                "Unsupported record fields");
            for (Map.Entry<String, JsonElement> field : input.getAsJsonObject().entrySet()) {
                validateShape(field.getValue(), decoded.getAsJsonObject().get(field.getKey()));
            }
        } else if (input.isJsonArray() && decoded.isJsonArray()) {
            require(input.getAsJsonArray().size() == decoded.getAsJsonArray().size(), "Invalid record array");
            for (int i = 0; i < input.getAsJsonArray().size(); i++) {
                validateShape(input.getAsJsonArray().get(i), decoded.getAsJsonArray().get(i));
            }
        } else {
            require(input.isJsonPrimitive() && decoded.isJsonPrimitive(), "Invalid record field type");
            require(input.getAsJsonPrimitive().isNumber() == decoded.getAsJsonPrimitive().isNumber()
                && input.getAsJsonPrimitive().isBoolean() == decoded.getAsJsonPrimitive().isBoolean(),
                "Invalid record value type");
            if (input.getAsJsonPrimitive().isNumber()) {
                require(input.getAsBigDecimal().compareTo(decoded.getAsBigDecimal()) == 0,
                    "Record number is outside its supported range");
            }
        }
    }

    private void put(Map<String, JsonElement> records, String key, Object record) {
        require(records.putIfAbsent(key, gson.toJsonTree(record)) == null, "Duplicate record identity: " + key);
    }

    private static <T> void ordered(TreeMap<Integer, T> records, Integer position, T value) {
        require(position != null && position >= 0 && records.putIfAbsent(position, value) == null,
            "Duplicate or invalid record position");
    }

    private static <T> List<T> contiguous(TreeMap<Integer, T> records) {
        if (records == null || records.isEmpty()) return Collections.emptyList();
        require(records.firstKey() == 0 && records.lastKey() == records.size() - 1, "Missing ordered record");
        return new ArrayList<>(records.values());
    }

    private static String historyKey(String prefix, Snapshot offer) {
        return prefix + "offers/" + offer.itemId + "/" + encode(offer.uuid);
    }

    private static String groupKey(String prefix, String recipeKey) {
        return prefix + "recipes/" + (recipeKey == null ? "null" : "key-" + encode(recipeKey));
    }

    private static String flipKey(String groupKey, Instant time) {
        return groupKey + "/flips/" + encode(time.toString());
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class AccountRecord {
        Integer formatVersion;
        String displayName;
        Instant sessionStartTime;
        Long accumulatedSessionTimeMillis;
        Instant lastSessionTimeUpdate;
        Instant lastStoredAt;
        Instant lastModifiedAt;
    }

    private static final class ItemRecord {
        Integer position;
        Integer itemId;
        String itemName;
        Boolean visible;
        Boolean favorite;
        String favoriteCode;
        Instant nextGeLimitRefresh;
        Integer itemsBoughtThisLimitWindow;
        Integer itemsBoughtThroughCompleteOffers;
    }

    private static final class Snapshot {
        String uuid;
        Boolean buy;
        Integer itemId;
        Integer quantity;
        Integer totalQuantity;
        Long price;
        Instant time;
        Integer slot;
        GrandExchangeOfferState state;
        Integer tickArrivedAt;
        Integer ticksSinceFirstOffer;
        Instant tradeStartedAt;
        Boolean beforeLogin;
    }

    private static final class OfferRecord {
        Integer position;
        Snapshot offer;
    }

    private static final class GroupRecord {
        Integer position;
        String recipeKey;
    }

    private static final class FlipRecord {
        Integer position;
        Instant timeOfCreation;
        Long coinCost;
        List<ComponentItem> inputs;
        List<ComponentItem> outputs;
    }

    private static final class ComponentItem {
        Integer itemId;
        List<ComponentRecord> offers;
    }

    private static final class ComponentRecord {
        String key;
        String offerUuid;
        Integer amountConsumed;
        Snapshot offer;
    }
}
