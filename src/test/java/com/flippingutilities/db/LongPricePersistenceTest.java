package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.utilities.Constants;
import com.flippingutilities.utilities.SlotState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LongPricePersistenceTest {
    private static final String ACCOUNT = "Long prices";
    private static final long BUY_PRICE = 3_000_000_001L;
    private static final long SELL_PRICE = 5_000_000_001L;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void cloneAndCloudPayloadKeepExactPricesAndSpent() {
        OfferEvent offer = offer(true, BUY_PRICE, 3);
        assertEquals(BUY_PRICE, offer.getPreTaxPrice());
        assertEquals(BUY_PRICE, offer.clone().getListedPrice());
        assertEquals(BUY_PRICE * 3, offer.clone().getSpent());

        JsonObject payload = new Gson().toJsonTree(SlotState.fromOfferEvent(offer)).getAsJsonObject();
        assertTrue(payload.getAsJsonPrimitive("offerPrice").isNumber());
        assertEquals(BUY_PRICE, payload.get("offerPrice").getAsLong());
        assertEquals(BUY_PRICE * 3, payload.get("filledPrice").getAsLong());
        assertEquals(3, payload.get("filledQty").getAsInt());
        assertEquals(123L, new Gson().fromJson("{\"p\":123}", OfferEvent.class).getPreTaxPrice());
    }

    @Test
    public void liveSqliteWritesRoundTripLongPricesAndTaxedProfits() throws Exception {
        AccountData source = account();
        File database = folder.newFile("live.db");
        SqliteStorage storage = new SqliteStorage(database);
        try {
            storage.initializeSchema();
            for (OfferEvent offer : offers(source)) {
                storage.recordTrade(ACCOUNT, offer);
            }
            try (Statement statement = storage.getConnection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT price FROM trades ORDER BY timestamp")) {
                assertTrue(rows.next());
                assertEquals(BUY_PRICE, rows.getLong(1));
                assertTrue(rows.next());
                assertEquals(SELL_PRICE, rows.getLong(1));
            }
            storage.close();
            assertMoney(storage.loadAccount(ACCOUNT));
        } finally {
            storage.close();
        }
    }

    @Test
    public void jsonMigrationAndCsvKeepLongPrices() throws Exception {
        TradePersister persister = new TradePersister(new Gson(), folder.newFolder("accounts"));
        persister.writeToFile(ACCOUNT, account());
        AccountData fromJson = persister.loadAccount(ACCOUNT);
        assertMoney(fromJson);

        SqliteStorage storage = new SqliteStorage(folder.newFile("migrated.db"));
        try {
            assertEquals(1, new MigrationService(storage, persister).migrate());
            assertMoney(storage.loadAccount(ACCOUNT));
            try (Statement statement = storage.getConnection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT MAX(price) FROM trades")) {
                assertTrue(rows.next());
                assertEquals(SELL_PRICE, rows.getLong(1));
            }
        } finally {
            storage.close();
        }

        File csv = folder.newFile("export.csv");
        TradePersister.exportToCsv(csv, fromJson.getTrades(), "All time");
        String exported = Files.readString(csv.toPath());
        assertTrue(exported.contains(",3000000001,BOUGHT"));
        assertTrue(exported.contains(",4995000001,SOLD"));
        assertTrue(exported.contains("Total profit: 5985000000"));
    }

    @Test
    public void taxTotalsAndWeightedFlipPricesDoNotNarrowToInt() {
        OfferEvent oldTax = offer(false, SELL_PRICE, 1000);
        oldTax.setTime(Instant.ofEpochSecond(Constants.GE_TAX_START + 1));
        assertEquals(SELL_PRICE - Constants.GE_TAX_CAP, oldTax.getPrice());
        assertEquals(5_000_000_000L, oldTax.getTaxPaid());

        OfferEvent firstBuy = offer(true, BUY_PRICE, 1);
        OfferEvent secondBuy = offer(true, BUY_PRICE + 3, 2);
        secondBuy.setTime(TIME.plusSeconds(1));
        OfferEvent sale = offer(false, SELL_PRICE, 3);
        sale.setTime(TIME.plusSeconds(2));
        Flip flip = HistoryManager.getFlips(Arrays.asList(firstBuy, secondBuy, sale)).get(0);
        assertEquals(BUY_PRICE + 2, flip.getBuyPrice());
        assertEquals(SELL_PRICE - Constants.GE_TAX_CAP, flip.getSellPrice());
    }

    private AccountData account() {
        OfferEvent buy = offer(true, BUY_PRICE, 3);
        OfferEvent sell = offer(false, SELL_PRICE, 3);
        sell.setTime(TIME.plusSeconds(60));
        FlippingItem item = new FlippingItem(4151, "Abyssal whip", 70, ACCOUNT);
        item.setValidFlippingPanelItem(true);
        item.getHistory().setCompressedOfferEvents(Arrays.asList(buy, sell));
        AccountData account = new AccountData();
        account.setTrades(Collections.singletonList(item));
        return account;
    }

    private List<OfferEvent> offers(AccountData account) {
        return account.getTrades().get(0).getHistory().getCompressedOfferEvents();
    }

    private void assertMoney(AccountData account) {
        List<OfferEvent> offers = offers(account);
        assertEquals(BUY_PRICE, offers.get(0).getPreTaxPrice());
        assertEquals(SELL_PRICE, offers.get(1).getPreTaxPrice());
        assertEquals(15_000_000L, offers.get(1).getTaxPaid());
        assertEquals(5_985_000_000L, FlippingItem.getProfit(offers));
        Flip flip = HistoryManager.getFlips(offers).get(0);
        assertEquals(BUY_PRICE, flip.getBuyPrice());
        assertEquals(SELL_PRICE - Constants.GE_TAX_CAP, flip.getSellPrice());
    }

    @Test
    public void clientEventUsesTheFullMonetaryRangeSupportedByTheApi() throws Exception {
        boolean longApi = GrandExchangeOffer.class.getMethod("getSpent").getReturnType() == long.class
            && GrandExchangeOffer.class.getMethod("getPrice").getReturnType() == long.class;
        // Older RuneLite releases expose int amounts. New releases exercise values above
        // that range through this same client boundary; storage tests always use longs.
        long price = longApi ? BUY_PRICE : 700_000_001L;
        int quantity = 3;
        GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
            GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getQuantitySold":
                    case "getTotalQuantity": return quantity;
                    case "getItemId": return 4151;
                    case "getPrice": return apiMoney(price, method.getReturnType());
                    case "getSpent": return apiMoney(price * quantity, method.getReturnType());
                    case "getState": return GrandExchangeOfferState.BOUGHT;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
        event.setOffer(offer);
        event.setSlot(2);
        OfferEvent converted = OfferEvent.fromGrandExchangeEvent(event);
        assertEquals(price, converted.getPreTaxPrice());
        assertEquals(price, converted.getListedPrice());
        assertEquals(price * quantity, converted.getSpent());
    }

    private Object apiMoney(long value, Class<?> returnType) {
        if (returnType == long.class) {
            return value;
        }
        return Math.toIntExact(value);
    }

    private OfferEvent offer(boolean buy, long price, int quantity) {
        return new OfferEvent(UUID.randomUUID().toString(), buy, 4151, quantity, price, TIME,
            2, buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD,
            10, 10, quantity, null, false, ACCOUNT, null, price, price * quantity);
    }
}
