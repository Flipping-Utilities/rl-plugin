package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

/** Exercises the live writer through the same account loader used at startup. */
public class LiveTradePersistenceTest {
    private static final String ACCOUNT = "LivePlayer";
    private static final int WHIP = 4151;
    // Before GE tax, to keep the expected profits simple.
    private static final long BASE_TIME = 1600000000000L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File database;
    private SqliteStorage storage;

    @Before
    public void setUp() throws Exception {
        database = temporaryFolder.newFile("trades.db");
        storage = new SqliteStorage(database);
        storage.initializeSchema();
    }

    @After
    public void tearDown() {
        storage.close();
    }

    @Test
    public void liveTradeCreatesAccountAndSurvivesReopen() {
        OfferEvent expected = complete(ACCOUNT, WHIP, "buy", BASE_TIME, 10, 50000, true);
        storage.recordTrade(ACCOUNT, expected);
        storage.close();
        storage = new SqliteStorage(database);

        List<OfferEvent> offers = loadedOffers();
        assertEquals(1, offers.size());
        OfferEvent actual = offers.get(0);
        assertEquals(expected.getUuid(), actual.getUuid());
        assertEquals(expected.getItemId(), actual.getItemId());
        assertEquals(expected.getCurrentQuantityInTrade(), actual.getCurrentQuantityInTrade());
        assertEquals(expected.getPreTaxPrice(), actual.getPreTaxPrice());
        assertEquals(expected.getTime(), actual.getTime());
        assertEquals(expected.getState(), actual.getState());
        assertEquals(ACCOUNT, actual.getMadeBy());
    }

    @Test
    public void buyWithoutSellHasNoRealizedProfit() {
        record("buy", BASE_TIME, 10, 50000, true);
        assertEquals(0L, loadedProfit());
    }

    @Test
    public void completedSellsRestoreTheirMatchedProfit() {
        record("buy", BASE_TIME, 10, 50000, true);
        record("sell", BASE_TIME + 60000, 10, 55000, false);
        assertEquals(50000L, loadedProfit());
    }

    @Test
    public void separateSellsRestoreIncrementalProfit() {
        record("buy", BASE_TIME, 10, 50000, true);
        record("sell-first", BASE_TIME + 60000, 3, 55000, false);
        assertEquals(15000L, loadedProfit());

        record("sell-rest", BASE_TIME + 120000, 7, 54000, false);
        assertEquals(43000L, loadedProfit());
    }

    @Test
    public void distinctOffersWithIdenticalValuesArePreserved() {
        record("buy-first", BASE_TIME, 5, 50000, true);
        record("buy-second", BASE_TIME, 5, 50000, true);
        record("sell-first", BASE_TIME + 60000, 5, 55000, false);
        record("sell-second", BASE_TIME + 60000, 5, 55000, false);

        assertEquals(4, loadedOffers().size());
        assertEquals(50000L, loadedProfit());
    }

    @Test
    public void repeatedOfferKeepsOneTradeAndItsActualTax() throws Exception {
        OfferEvent sale = complete(ACCOUNT, WHIP, "taxed-sale",
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), 10, 1000, false);
        storage.recordTrade(ACCOUNT, sale);
        storage.recordTrade(ACCOUNT, sale);

        assertEquals(1, loadedOffers().size());
        assertEquals(200L, sale.getTaxPaid());
        assertEquals(sale.getTaxPaid(), loadedOffers().get(0).getTaxPaid());
        try (Statement statement = storage.getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT tax FROM trades")) {
            assertTrue(result.next());
            assertEquals(sale.getTaxPaid(), result.getLong(1));
            assertFalse(result.next());
        }
    }

    @Test
    public void sameUuidOnDifferentAccountsDoesNotDeduplicate() {
        record("shared-uuid", BASE_TIME, 10, 50000, true);
        storage.recordTrade("OtherPlayer",
            complete("OtherPlayer", WHIP, "shared-uuid", BASE_TIME, 7, 60000, true));

        assertEquals(2, storage.listAccounts().size());
        assertEquals(10, loadedOffers().get(0).getCurrentQuantityInTrade());
        AccountData other = storage.loadAccount("OtherPlayer");
        assertEquals(7, other.getTrades().get(0).getHistory().getCompressedOfferEvents()
            .get(0).getCurrentQuantityInTrade());
    }

    private void record(String uuid, long time, int quantity, int price, boolean buy) {
        storage.recordTrade(ACCOUNT, complete(ACCOUNT, WHIP, uuid, time, quantity, price, buy));
    }

    private List<OfferEvent> loadedOffers() {
        AccountData account = storage.loadAccount(ACCOUNT);
        assertNotNull(account);
        assertEquals(1, account.getTrades().size());
        return account.getTrades().get(0).getHistory().getCompressedOfferEvents();
    }

    private long loadedProfit() {
        return FlippingItem.getProfit(loadedOffers());
    }
}
